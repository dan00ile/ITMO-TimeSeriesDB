package org.tsdb.cluster;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.tsdb.db.LocalTimeSeriesDB;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalRecord;

/** Применение журнала лидера. DB и транспортный channel принадлежат вызывающему коду. */
public final class FollowerLoop implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FollowerLoop.class.getName());
    private final String nodeId;
    private final LocalTimeSeriesDB db;
    private final ReplicationTransport transport;
    private final FollowerCheckpoint checkpoint;
    private final Context.CancellableContext lifetime = Context.ROOT.withCancellation();
    private final Object ackLock = new Object();
    private final Object checkpointLock = new Object();
    private final CountDownLatch stopping = new CountDownLatch(1);
    private final ScheduledExecutorService scheduler;
    private final Thread worker;
    private volatile ReplicationProgress progress;
    private ReplicationProgress durableProgress;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile RuntimeException failure;
    private ReplicationTransport.Session session; // под ackLock
    private Context.CancellableContext connectionContext; // под ackLock
    private boolean started;
    private boolean closed;

    /** Использует файловый ReplicationStateStore части A в каталоге текущей БД. */
    public FollowerLoop(String nodeId, LocalTimeSeriesDB db, ReplicationTransport transport) {
        this(nodeId, db, transport, new StoredFollowerCheckpoint(db));
    }

    public FollowerLoop(String nodeId, LocalTimeSeriesDB db, ReplicationTransport transport,
                        FollowerCheckpoint checkpoint) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.db = Objects.requireNonNull(db, "db");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        progress = Objects.requireNonNull(checkpoint.load(), "checkpoint.load()");
        durableProgress = progress;
        worker = new Thread(this::run, "tsdb-follower");
        worker.setDaemon(true);
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tsdb-follower-ack");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("follower is closed");
        if (started) return;
        started = true;
        running = true;
        scheduler.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        worker.start();
    }

    public ReplicationProgress progress() { return progress; }
    public boolean connected() { return connected; }
    /** Неисправимая ошибка WAL/state/протокола; сетевые обрывы сюда не попадают. */
    public RuntimeException failure() { return failure; }

    private void run() {
        long backoffMs = 100;
        try {
            while (running) {
                ReplicationProgress before = progress;
                try (Context.CancellableContext attempt = lifetime.withCancellation()) {
                    attempt.run(() -> consume(attempt));
                } catch (StatusRuntimeException e) {
                    if (!running) break;
                    if (!retryable(e.getStatus().getCode())) throw e;
                    LOG.log(System.Logger.Level.WARNING, "replication stream broken: {0}", e.getStatus());
                }
                if (!running) break;
                // Сброс после прогресса: успешное открытие пустого оборванного стрима не создаёт busy loop.
                if (!before.equals(progress)) backoffMs = 100;
                stopping.await(backoffMs, TimeUnit.MILLISECONDS);
                backoffMs = Math.min(5000, backoffMs * 2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (running) fail(new IllegalStateException("follower interrupted", e));
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            connected = false;
            running = false;
            scheduler.shutdown();
        }
    }

    private void consume(Context.CancellableContext context) {
        ReplicationTransport.Session opened = transport.connect(nodeId, progress.applied());
        try {
            synchronized (ackLock) {
                session = opened;
                connectionContext = context;
                connected = running && !context.isCancelled();
            }
            Iterator<WalEntry> entries = opened.entries();
            while (running && !context.isCancelled() && entries.hasNext()) {
                if (!running || context.isCancelled()) break;
                apply(entries.next());
            }
        } finally {
            synchronized (ackLock) {
                connected = false;
                session = null;
                connectionContext = null;
                opened.close();
            }
        }
    }

    private void apply(WalEntry entry) {
        ReplicationProgress previous = progress;
        if (previous.applied() != null && entry.position().compareTo(previous.applied()) <= 0) return;
        db.validateRecord(entry.record());
        db.wal().append(entry.record());
        db.applyRecord(entry.record());
        long maxTs = previous.appliedMaxTs();
        if (entry.record() instanceof WalRecord.Samples samples) {
            for (var sample : samples.entries()) maxTs = Math.max(maxTs, sample.timestamp());
        }
        // Публикуем позицию вместе с временем только после полного применения записи.
        progress = new ReplicationProgress(entry.position(), maxTs);
    }

    private ReplicationProgress persist() {
        synchronized (checkpointLock) {
            // Снимок ДО sync: даже если писатель успеет дальше, state не обгонит durable WAL.
            ReplicationProgress snapshot = progress;
            if (!snapshot.equals(durableProgress)) {
                db.wal().sync();
                checkpoint.save(snapshot);
                durableProgress = snapshot;
            }
            return durableProgress;
        }
    }

    private void tick() {
        try {
            ReplicationProgress durable = persist();
            synchronized (ackLock) {
                if (running && connected && session != null && durable.applied() != null) {
                    try {
                        session.ack(durable);
                    } catch (StatusRuntimeException e) {
                        // Будит блокирующий Replicate и пересоздаёт оба RPC одной сессии.
                        connectionContext.cancel(e);
                    }
                }
            }
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException error) {
        failure = error;
        running = false;
        stopping.countDown();
        lifetime.cancel(error);
        LOG.log(System.Logger.Level.ERROR, "follower stopped", error);
    }

    private static boolean retryable(Status.Code code) {
        return switch (code) {
            case CANCELLED, UNKNOWN, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, INTERNAL, UNAVAILABLE -> true;
            default -> false;
        };
    }

    /** Останавливает оба RPC, ждёт писателя и сохраняет финальную позицию; DB не закрывает. */
    @Override public synchronized void close() {
        if (closed) return;
        running = false;
        stopping.countDown();
        lifetime.cancel(null);
        // Не прерываем FileChannel во время append/sync: interrupt может закрыть WAL.
        scheduler.shutdown();
        try {
            worker.join(5000);
            if (worker.isAlive() || !scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("replication did not stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping replication", e);
        }
        connected = false;
        persist();
        closed = true;
    }
}
