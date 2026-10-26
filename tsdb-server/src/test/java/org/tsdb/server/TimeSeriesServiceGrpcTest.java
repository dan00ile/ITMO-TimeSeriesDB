package org.tsdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.db.DbOptions;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.proto.Aggregation;
import org.tsdb.proto.Empty;
import org.tsdb.proto.Label;
import org.tsdb.proto.LabelNamesRequest;
import org.tsdb.proto.LabelValuesRequest;
import org.tsdb.proto.Matcher;
import org.tsdb.proto.QueryRequest;
import org.tsdb.proto.QueryResponse;
import org.tsdb.proto.Sample;
import org.tsdb.proto.TimeSeries;
import org.tsdb.proto.TimeSeriesServiceGrpc;
import org.tsdb.proto.WriteRequest;
import org.tsdb.proto.WriteResponse;

// Сквозные тесты сервиса через gRPC в одной JVM: настоящий TimeSeriesServiceImpl поверх настоящей БД.
class TimeSeriesServiceGrpcTest {
    @TempDir
    Path dir;

    private TimeSeriesDB db;
    private Server server;
    private ManagedChannel channel;
    private TimeSeriesServiceGrpc.TimeSeriesServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        db = TimeSeriesDB.open(DbOptions.builder(dir).compactionIntervalMs(0).build());
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(new TimeSeriesServiceImpl(db)).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        stub = TimeSeriesServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        db.close();
    }

    private static Label label(String name, String value) {
        return Label.newBuilder().setName(name).setValue(value).build();
    }

    private static TimeSeries series(String host, long... timestamps) {
        TimeSeries.Builder b = TimeSeries.newBuilder()
                .addLabels(label("host", host))          // порядок лейблов не важен: сервер сортирует
                .addLabels(label("__name__", "cpu_usage"));
        for (long ts : timestamps) {
            b.addSamples(Sample.newBuilder().setTimestampMs(ts).setValue(ts / 1000.0));
        }
        return b.build();
    }

    private static Matcher eq(String name, String value) {
        return Matcher.newBuilder().setType(Matcher.Type.EQ).setName(name).setValue(value).build();
    }

    private static QueryRequest.Builder cpu(long start, long end) {
        return QueryRequest.newBuilder().addMatchers(eq("__name__", "cpu_usage")).setStartMs(start).setEndMs(end);
    }

    @Test
    void writeThenQuery() {
        WriteResponse w = stub.write(WriteRequest.newBuilder()
                .addSeries(series("web-1", 1_000, 2_000))
                .addSeries(series("web-2", 1_000))
                .build());
        assertEquals(3, w.getAccepted());
        assertEquals(0, w.getRejected());

        QueryResponse q = stub.query(cpu(0, 5_000).addMatchers(
                Matcher.newBuilder().setType(Matcher.Type.RE).setName("host").setValue("web-.*")).build());
        assertEquals(2, q.getSeriesCount());
        TimeSeries first = q.getSeries(0);
        assertEquals(List.of(label("__name__", "cpu_usage"), label("host", "web-1")), first.getLabelsList());
        assertEquals(List.of(1_000L, 2_000L), first.getSamplesList().stream().map(Sample::getTimestampMs).toList());
    }

    @Test
    void outOfOrderIsCountedNotFailed() {
        stub.write(WriteRequest.newBuilder().addSeries(series("web-1", 2_000)).build());
        WriteResponse w = stub.write(WriteRequest.newBuilder().addSeries(series("web-1", 1_000, 3_000)).build());
        assertEquals(1, w.getAccepted());
        assertEquals(1, w.getRejected());
        assertEquals(1, w.getErrorsCount());
    }

    @Test
    void aggregatedQuery() {
        stub.write(WriteRequest.newBuilder()
                .addSeries(series("a", 1_000, 2_000))   // значения 1.0, 2.0
                .addSeries(series("b", 1_000))           // значение 1.0
                .build());
        QueryResponse q = stub.query(cpu(0, 5_000)
                .setAggregation(Aggregation.SUM).addGroupBy("host").build());
        assertEquals(2, q.getSeriesCount());
        assertEquals(List.of(label("host", "a")), q.getSeries(0).getLabelsList());
        assertEquals(3.0, q.getSeries(0).getSamples(0).getValue(), 1e-9);
    }

    @Test
    void writeStreamWithThousandMessages() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WriteResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<WriteRequest> requests = TimeSeriesServiceGrpc.newStub(channel)
                .writeStream(new StreamObserver<>() {
                    @Override
                    public void onNext(WriteResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });
        for (int i = 1; i <= 1_000; i++) {
            requests.onNext(WriteRequest.newBuilder().addSeries(series("web-1", i * 1_000L)).build());
        }
        requests.onCompleted();

        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(null, error.get());
        assertEquals(1_000, response.get().getAccepted());
        assertEquals(1_000, stub.query(cpu(0, 2_000_000).build()).getSeries(0).getSamplesCount());
    }

    @Test
    void invalidArgumentsMapToInvalidArgument() {
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.query(cpu(5_000, 1_000).build()));      // end < start
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.query(QueryRequest.newBuilder()          // нет Eq
                .addMatchers(Matcher.newBuilder().setType(Matcher.Type.NEQ).setName("host").setValue("a"))
                .setEndMs(1).build()));
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.query(cpu(0, 1).addGroupBy("host").build()));
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.write(WriteRequest.newBuilder()          // нет __name__
                .addSeries(TimeSeries.newBuilder().addLabels(label("host", "a"))
                        .addSamples(Sample.newBuilder().setTimestampMs(1)))
                .build()));
        assertCode(Status.Code.INVALID_ARGUMENT, () -> stub.write(WriteRequest.newBuilder()          // плохое имя
                .addSeries(TimeSeries.newBuilder().addLabels(label("__name__", "m")).addLabels(label("1bad", "x"))
                        .addSamples(Sample.newBuilder().setTimestampMs(1)))
                .build()));
    }

    @Test
    void labelsStatsAndFlush() {
        stub.write(WriteRequest.newBuilder().addSeries(series("web-1", 1_000)).addSeries(series("web-2", 1_000)).build());

        assertTrue(stub.labelNames(LabelNamesRequest.getDefaultInstance()).getNamesList().contains("host"));
        assertEquals(List.of("web-1", "web-2"), stub.labelValues(LabelValuesRequest.newBuilder().setName("host").build())
                .getValuesList().stream().sorted().toList());

        assertEquals(2, stub.getStats(Empty.getDefaultInstance()).getSeries());
        assertEquals(2, stub.getStats(Empty.getDefaultInstance()).getHeadSamples());

        stub.flush(Empty.getDefaultInstance());
        assertEquals(1, stub.getStats(Empty.getDefaultInstance()).getBlocks());
        assertEquals(0, stub.getStats(Empty.getDefaultInstance()).getHeadSamples());
        assertEquals(2, stub.query(cpu(0, 5_000).build()).getSeriesCount(), "после flush данные читаются из блока");
    }

    private static void assertCode(Status.Code code, Runnable call) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(code, e.getStatus().getCode(), e.getStatus().toString());
    }
}
