package org.tsdb.cli;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.tsdb.bench.MetricsGenerator;
import org.tsdb.db.SampleInput;
import org.tsdb.model.Labels;
import org.tsdb.proto.TimeSeries;
import org.tsdb.proto.TimeSeriesServiceGrpc;
import org.tsdb.proto.WriteRequest;
import org.tsdb.proto.WriteResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

// Генератор нагрузки: серии cpu_usage{host, core} из MetricsGenerator, по точке на серию раз в --interval,
// одним client-streaming WriteStream. Сообщение = один тик всех серий.
//   tsdb-cli load --series 1000 --interval 15s --duration 10m          — в реальном времени (10 минут)
//   tsdb-cli load --series 1000 --interval 15s --duration 24h --fast   — задним числом [now-24h, now), без пауз
//   tsdb-cli load --duration 0                                         — бесконечно, до Ctrl-C
@Command(name = "load", description = "Генератор нагрузки через WriteStream.")
final class LoadCommand implements Callable<Integer> {
    private static final long PROGRESS_EVERY_NANOS = TimeUnit.SECONDS.toNanos(5);

    @ParentCommand
    Main root;

    @Spec
    CommandSpec spec;

    @Option(names = "--series", defaultValue = "1000", description = "Число серий (по умолчанию ${DEFAULT-VALUE}).")
    int series;

    @Option(names = "--cores", defaultValue = "4",
            description = "Серий на хост: core=0..N-1 (по умолчанию ${DEFAULT-VALUE}).")
    int cores;

    @Option(names = "--interval", defaultValue = "15s", description = "Шаг точек (по умолчанию ${DEFAULT-VALUE}).")
    String interval;

    @Option(names = "--duration", defaultValue = "1m",
            description = "Сколько данных записать; 0 — бесконечно (по умолчанию ${DEFAULT-VALUE}).")
    String duration;

    @Option(names = "--fast", description = "Не ждать часов: записать точки за [now-duration, now) как можно быстрее.")
    boolean fast;

    @Option(names = "--seed", defaultValue = "42", description = "Seed генератора значений.")
    long seed;

    private volatile boolean stopping;

    @Override
    public Integer call() throws InterruptedException {
        long step = TimeArgs.durationMs(interval);
        long length = TimeArgs.durationMs(duration);
        if (step <= 0 || series <= 0 || cores <= 0) {
            throw new IllegalArgumentException("--series, --cores and --interval must be > 0");
        }
        if (fast && length == 0) {
            throw new IllegalArgumentException("--fast needs a finite --duration");
        }
        int perHost = Math.min(cores, series);
        int hosts = (series + perHost - 1) / perHost;
        long ticks = length == 0 ? Long.MAX_VALUE : Math.max(1, length / step);
        long now = System.currentTimeMillis();
        long start = fast ? now - ticks * step : now;

        // генератор строит hosts × cores серий; у последнего хоста лишние отбрасываем — ровно --series
        MetricsGenerator gen = new MetricsGenerator(hosts, perHost, start, step, seed);
        List<List<org.tsdb.proto.Label>> protoLabels = new ArrayList<>(series);
        for (Labels l : gen.series().subList(0, series)) {
            protoLabels.add(ProtoConvert.labels(l));
        }

        PrintWriter out = spec.commandLine().getOut();
        out.printf("load: %d series (%d hosts × up to %d cores), step %s, %s%s → %s%n",
                series, hosts, perHost, interval,
                length == 0 ? "until Ctrl-C" : duration, fast ? " backfilled" : " real time", root.addr);
        out.flush();

        // Ctrl-C: дописать текущее сообщение, закрыть поток (onCompleted) и напечатать итог, потом выйти
        CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stopping = true;
            try {
                finished.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "tsdb-cli-load-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        ManagedChannel channel = root.channel();
        try {
            return stream(gen, protoLabels, ticks, out, channel);
        } finally {
            Main.close(channel);
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Ctrl-C: JVM уже выходит
            }
        }
    }

    private int stream(MetricsGenerator gen, List<List<org.tsdb.proto.Label>> protoLabels, long ticks,
                       PrintWriter out, ManagedChannel channel) throws InterruptedException {
        Flow flow = new Flow();
        TimeSeriesServiceGrpc.newStub(channel).writeStream(flow);
        ClientCallStreamObserver<WriteRequest> call = flow.call;

        long t0 = System.nanoTime();
        long lastProgress = t0;
        long sent = 0;
        for (long tick = 0; tick < ticks && !stopping && flow.error == null; tick++) {
            long ts = gen.nextTimestamp();
            // реальное время: ждём, пока часы дойдут до ts; короткими шагами, чтобы Ctrl-C срабатывал сразу
            while (!fast && !stopping && System.currentTimeMillis() < ts) {
                Thread.sleep(Math.max(1, Math.min(200, ts - System.currentTimeMillis())));
            }
            if (stopping) {
                break;
            }
            WriteRequest request = toRequest(gen.nextTick(), protoLabels);
            if (!flow.awaitReady()) {
                break;
            }
            call.onNext(request);
            sent += request.getSeriesCount();
            if (System.nanoTime() - lastProgress >= PROGRESS_EVERY_NANOS) {
                lastProgress = System.nanoTime();
                out.printf("  sent %,d points (%,.0f/s)%n", sent, sent / seconds(t0));
                out.flush();
            }
        }
        if (flow.error == null) {
            call.onCompleted();
        }
        flow.done.await(30, TimeUnit.SECONDS);
        if (flow.error != null) {
            throw flow.error instanceof StatusRuntimeException s ? s : Status.fromThrowable(flow.error).asRuntimeException();
        }
        WriteResponse r = flow.response;
        out.printf("sent %,d points in %.1f s (%,.0f/s); accepted %,d, rejected %,d%n",
                sent, seconds(t0), sent / seconds(t0),
                r == null ? 0 : r.getAccepted(), r == null ? 0 : r.getRejected());
        if (r != null) {
            r.getErrorsList().forEach(e -> out.println("  " + e));
        }
        out.flush();
        return 0;
    }

    private static WriteRequest toRequest(List<SampleInput> tick, List<List<org.tsdb.proto.Label>> protoLabels) {
        WriteRequest.Builder b = WriteRequest.newBuilder();
        for (int i = 0; i < protoLabels.size(); i++) {
            SampleInput s = tick.get(i); // порядок совпадает с gen.series()
            b.addSeries(TimeSeries.newBuilder()
                    .addAllLabels(protoLabels.get(i))
                    .addSamples(org.tsdb.proto.Sample.newBuilder()
                            .setTimestampMs(s.timestamp()).setValue(s.value())));
        }
        return b.build();
    }

    private static double seconds(long t0) {
        return Math.max(1e-3, (System.nanoTime() - t0) / 1e9);
    }

    // Ответ сервера + backpressure: onNext только когда транспорт готов принять (isReady),
    // иначе клиент копит сообщения в памяти быстрее, чем сервер их принимает.
    private final class Flow implements ClientResponseObserver<WriteRequest, WriteResponse> {
        final CountDownLatch done = new CountDownLatch(1);
        final ReentrantLock lock = new ReentrantLock();
        final Condition ready = lock.newCondition();
        ClientCallStreamObserver<WriteRequest> call;
        volatile WriteResponse response;
        volatile Throwable error;

        @Override
        public void beforeStart(ClientCallStreamObserver<WriteRequest> requestStream) {
            call = requestStream;
            requestStream.setOnReadyHandler(this::wake);
        }

        boolean awaitReady() throws InterruptedException {
            lock.lock();
            try {
                while (!call.isReady() && error == null && !stopping) {
                    ready.await(100, TimeUnit.MILLISECONDS);
                }
                return error == null && !stopping;
            } finally {
                lock.unlock();
            }
        }

        private void wake() {
            lock.lock();
            try {
                ready.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onNext(WriteResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            wake();
            done.countDown();
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }
    }
}
