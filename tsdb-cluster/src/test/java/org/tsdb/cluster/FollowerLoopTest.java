package org.tsdb.cluster;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Context;
import io.grpc.Status;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.db.DbOptions;
import org.tsdb.db.LocalTimeSeriesDB;
import org.tsdb.model.Labels;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

class FollowerLoopTest {
    @TempDir Path dir;
    private static final Labels CPU = Labels.of("cpu_usage", "host", "n1");
    private static final WalEntry SERIES = entry(10, new WalRecord.SeriesCreated(42, CPU));
    private static final WalEntry SAMPLE = entry(20,
            new WalRecord.Samples(List.of(new SampleEntry(42, 1000, 0.5))));

    @Test
    void appliesLeaderIdsPersistsBeforeAckAndStopsBlockedStream() throws Exception {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        TestTransport transport = new TestTransport(List.of(SERIES, SAMPLE), false);
        transport.onAck = p -> assertEquals(p, checkpoint.saved.get(), "checkpoint must precede Ack");
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint)) {
            loop.start();
            await(() -> transport.acks.stream().anyMatch(p -> SAMPLE.position().equals(p.applied())));
            assertEquals(new ReplicationProgress(SAMPLE.position(), 1000), loop.progress());
            assertEquals(loop.progress(), checkpoint.saved.get());
            assertEquals(1, db.stats().headSamples());
            assertEquals("tsdb-follower", transport.threadName);
            assertEquals(1, transport.ackThreads.size());
            assertTrue(loop.connected());
        }
        assertTrue(transport.closed);
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(1, db.stats().headSamples());
            db.append(Labels.of("new_metric"), 2000, 1);
            try (var records = db.wal().readFrom(new WalPosition(1, 0))) {
                assertTrue(records.anyMatch(e -> e.record() instanceof WalRecord.SeriesCreated c && c.id() == 43));
            }
        }
    }

    @Test
    void reconnectsFromLastAppliedAndIgnoresDuplicateDelivery() throws Exception {
        TestTransport transport = new TestTransport(List.of(SERIES, SAMPLE, SAMPLE), true);
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop =
                new FollowerLoop("n2", db, transport, new MemoryCheckpoint())) {
            loop.start();
            await(() -> transport.afters.size() >= 2);
            assertNull(transport.afters.get(0));
            assertEquals(SAMPLE.position(), transport.afters.get(1));
            assertEquals(1, db.stats().headSamples());
            try (var records = db.wal().readFrom(new WalPosition(1, 0))) {
                assertEquals(2, records.count());
            }
        }
    }

    @Test
    void resumesAfterRestartWithSavedLeaderPosition() throws Exception {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db,
                new TestTransport(List.of(SERIES, SAMPLE), false), checkpoint)) {
            loop.start();
            await(() -> SAMPLE.position().equals(loop.progress().applied()));
        }
        WalEntry next = entry(30, new WalRecord.Samples(List.of(new SampleEntry(42, 2000, 0.75))));
        TestTransport transport = new TestTransport(List.of(next), false);
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint)) {
            loop.start();
            await(() -> next.position().equals(loop.progress().applied()));
            assertEquals(SAMPLE.position(), transport.afters.get(0));
            assertEquals(2, db.stats().headSamples());
            assertEquals(2000, loop.progress().appliedMaxTs());
        }
    }

    @Test
    void cancellationInterruptsConnectBeforeSessionExists() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        ReplicationTransport transport = (id, after) -> {
            Context context = Context.current();
            connecting.countDown();
            blockUntilCancelled(context);
            throw Status.CANCELLED.asRuntimeException();
        };
        try (LocalTimeSeriesDB db = open()) {
            FollowerLoop loop = new FollowerLoop("n2", db, transport, new MemoryCheckpoint());
            loop.start();
            assertTrue(connecting.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), loop::close);
            assertFalse(loop.connected());
        }
    }

    @Test
    void localApplyFailureStopsWithoutAckOrAdvancingCheckpoint() throws Exception {
        WalEntry invalid = entry(20, new WalRecord.Samples(List.of(new SampleEntry(99, 1000, 1))));
        TestTransport transport = new TestTransport(List.of(invalid), false);
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint)) {
            loop.start();
            await(() -> loop.failure() != null && !loop.connected());
            assertNull(loop.progress().applied());
            assertTrue(transport.acks.isEmpty());
            assertNull(checkpoint.saved.get().applied());
            assertFalse(loop.connected());
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(0, db.stats().headSamples(), "invalid record must not poison WAL replay");
        }
    }

    @Test
    void invalidBatchDoesNotPartiallyApplyOrPoisonWal() throws Exception {
        WalEntry invalid = entry(20, new WalRecord.Samples(List.of(
                new SampleEntry(42, 1000, 1), new SampleEntry(99, 2000, 2))));
        TestTransport transport = new TestTransport(List.of(SERIES, invalid), false);
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop =
                new FollowerLoop("n2", db, transport, new MemoryCheckpoint())) {
            loop.start();
            await(() -> loop.failure() != null && !loop.connected());
            assertEquals(SERIES.position(), loop.progress().applied());
            assertEquals(0, db.stats().headSamples());
        }
        try (LocalTimeSeriesDB db = open(); var records = db.wal().readFrom(new WalPosition(1, 0))) {
            assertEquals(1, records.count(), "only valid SeriesCreated belongs in WAL");
            assertEquals(0, db.stats().headSamples());
        }
    }

    @Test
    void conflictingSeriesDoesNotPoisonWal() throws Exception {
        WalEntry conflict = entry(20, new WalRecord.SeriesCreated(42, Labels.of("other_metric")));
        TestTransport transport = new TestTransport(List.of(SERIES, conflict), false);
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop =
                new FollowerLoop("n2", db, transport, new MemoryCheckpoint())) {
            loop.start();
            await(() -> loop.failure() != null && !loop.connected());
            assertEquals(SERIES.position(), loop.progress().applied());
        }
        try (LocalTimeSeriesDB db = open(); var records = db.wal().readFrom(new WalPosition(1, 0))) {
            assertEquals(List.of(SERIES.record()), records.map(WalEntry::record).toList());
        }
    }

    @Test
    void ackFailureReconnectsEvenWhileReplicationStreamIsIdle() throws Exception {
        TestTransport transport = new TestTransport(List.of(SERIES, SAMPLE), false);
        AtomicInteger attempts = new AtomicInteger();
        transport.onAck = ignored -> {
            if (attempts.getAndIncrement() == 0) throw Status.UNAVAILABLE.asRuntimeException();
        };
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop =
                new FollowerLoop("n2", db, transport, new MemoryCheckpoint())) {
            loop.start();
            await(() -> !transport.acks.isEmpty());
            assertTrue(transport.afters.size() >= 2);
            assertEquals(SAMPLE.position(), transport.afters.get(1));
            assertEquals(1, db.stats().headSamples());
            assertNull(loop.failure());
        }
    }

    @Test
    void checkpointFailureStopsAndNeverAcknowledgesUnsavedPosition() throws Exception {
        TestTransport transport = new TestTransport(List.of(SERIES, SAMPLE), false);
        RuntimeException diskFailure = new IllegalStateException("checkpoint disk failure");
        FollowerCheckpoint checkpoint = new FollowerCheckpoint() {
            @Override public ReplicationProgress load() { return ReplicationProgress.empty(); }
            @Override public void save(ReplicationProgress progress) { throw diskFailure; }
        };
        try (LocalTimeSeriesDB db = open()) {
            FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint);
            loop.start();
            await(() -> loop.failure() != null && !loop.connected());
            assertSame(diskFailure, loop.failure());
            assertTrue(transport.acks.isEmpty());
            assertSame(diskFailure, assertThrows(IllegalStateException.class, loop::close));
        }
    }

    @Test
    void expiredLeaderWalStopsInsteadOfRetryingForever() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        ReplicationTransport transport = (id, after) -> {
            connects.incrementAndGet();
            throw Status.FAILED_PRECONDITION.withDescription("resync required").asRuntimeException();
        };
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop =
                new FollowerLoop("n2", db, transport, new MemoryCheckpoint())) {
            loop.start();
            await(() -> loop.failure() != null);
            assertEquals(1, connects.get());
            assertNull(loop.progress().applied());
        }
    }

    @Test
    void staleCheckpointReplaysDurableRecordsWithoutDuplicatingSamples() throws Exception {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db,
                new TestTransport(List.of(SERIES, SAMPLE), false), checkpoint)) {
            loop.start();
            await(() -> SAMPLE.position().equals(loop.progress().applied()));
        }
        // Падение после WAL sync, но до сохранения новой позиции.
        checkpoint.saved.set(new ReplicationProgress(SERIES.position(), Long.MIN_VALUE));
        TestTransport transport = new TestTransport(List.of(SAMPLE), false);
        try (LocalTimeSeriesDB db = open(); FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint)) {
            loop.start();
            await(() -> SAMPLE.position().equals(loop.progress().applied()));
            assertEquals(SERIES.position(), transport.afters.get(0));
            assertEquals(1, db.stats().headSamples());
        }
    }

    private LocalTimeSeriesDB open() {
        return LocalTimeSeriesDB.open(DbOptions.builder(dir).walSyncIntervalMs(0)
                .compactionIntervalMs(0).build());
    }

    private static WalEntry entry(long offset, WalRecord record) {
        return new WalEntry(new WalPosition(7, offset), record);
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition not met before deadline");
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static void blockUntilCancelled(Context context) {
        CountDownLatch cancelled = new CountDownLatch(1);
        context.addListener(ignored -> cancelled.countDown(), Runnable::run);
        try {
            cancelled.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class MemoryCheckpoint implements FollowerCheckpoint {
        final AtomicReference<ReplicationProgress> saved = new AtomicReference<>(ReplicationProgress.empty());
        @Override public ReplicationProgress load() { return saved.get(); }
        @Override public void save(ReplicationProgress progress) { saved.set(progress); }
    }

    static final class TestTransport implements ReplicationTransport {
        final List<WalEntry> records;
        final boolean failFirst;
        final List<WalPosition> afters = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<ReplicationProgress> acks = new CopyOnWriteArrayList<>();
        final java.util.Set<String> ackThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean closed;
        volatile String threadName;
        java.util.function.Consumer<ReplicationProgress> onAck = ignored -> {};

        TestTransport(List<WalEntry> records, boolean failFirst) {
            this.records = records;
            this.failFirst = failFirst;
        }

        @Override public Session connect(String id, WalPosition after) {
            assertEquals("n2", id);
            threadName = Thread.currentThread().getName();
            afters.add(after);
            boolean fail = failFirst && afters.size() == 1;
            Context context = Context.current();
            Iterator<WalEntry> iterator = records.iterator();
            return new Session() {
                @Override public Iterator<WalEntry> entries() {
                    return new Iterator<>() {
                        @Override public boolean hasNext() {
                            if (iterator.hasNext()) return true;
                            if (fail) throw Status.UNAVAILABLE.asRuntimeException();
                            blockUntilCancelled(context);
                            throw Status.CANCELLED.asRuntimeException();
                        }
                        @Override public WalEntry next() { return iterator.next(); }
                    };
                }
                @Override public void ack(ReplicationProgress progress) {
                    onAck.accept(progress);
                    ackThreads.add(Thread.currentThread().getName());
                    acks.add(progress);
                }
                @Override public void close() { closed = true; }
            };
        }
    }
}
