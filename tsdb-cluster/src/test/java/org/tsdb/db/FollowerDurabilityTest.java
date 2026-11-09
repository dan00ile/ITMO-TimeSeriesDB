package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Context;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.cluster.FollowerCheckpoint;
import org.tsdb.cluster.FollowerLoop;
import org.tsdb.cluster.ReplicationProgress;
import org.tsdb.cluster.ReplicationTransport;
import org.tsdb.cluster.ReplicationStateStore;
import org.tsdb.cluster.StoredFollowerCheckpoint;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.head.Head;
import org.tsdb.model.Labels;
import org.tsdb.query.QueryEngine;
import org.tsdb.wal.Wal;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

/** Реальный WAL с наблюдением sync: при ненулевом интервале append сам не делает fsync. */
class FollowerDurabilityTest {
    @TempDir Path dir;

    @Test
    void syncPrecedesCheckpointAndAck() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch ack = new CountDownLatch(1);
        try (LocalTimeSeriesDB db = open(events, new AtomicBoolean());
             FollowerLoop loop = new FollowerLoop("n2", db, transport(p -> {
                 events.add("ack");
                 ack.countDown();
             }), checkpoint(db, events))) {
            loop.start();
            assertTrue(ack.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("sync", "save", "ack"), events.subList(0, 3));
            assertEquals(new WalPosition(7, 10), new ReplicationStateStore(dir).load().orElseThrow());
        }
    }

    @Test
    void syncFailurePreventsCheckpointAndAck() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean failSync = new AtomicBoolean(true);
        try (LocalTimeSeriesDB db = open(events, failSync)) {
            FollowerLoop loop = new FollowerLoop("n2", db, transport(p -> events.add("ack")), checkpoint(db, events));
            loop.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (loop.failure() == null && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(5);
            assertInstanceOf(UncheckedIOException.class, loop.failure());
            assertEquals(List.of("sync"), events, "failed sync must not publish state or Ack");
            assertFalse(Files.exists(dir.resolve("replication.state")));
            failSync.set(false);
            loop.close();
        }
    }

    private static FollowerCheckpoint checkpoint(LocalTimeSeriesDB db, List<String> events) {
        FollowerCheckpoint delegate = new StoredFollowerCheckpoint(db);
        return new FollowerCheckpoint() {
            @Override public ReplicationProgress load() { return delegate.load(); }
            @Override public void save(ReplicationProgress progress) {
                events.add("save");
                delegate.save(progress);
            }
        };
    }

    private LocalTimeSeriesDB open(List<String> events, AtomicBoolean failSync) {
        DbComponents defaults = new DefaultComponents();
        return LocalTimeSeriesDB.open(DbOptions.builder(dir).walSyncIntervalMs(60_000)
                .compactionIntervalMs(0).build(), new DbComponents() {
            @Override public Head newHead(DbOptions o) { return defaults.newHead(o); }
            @Override public BlockWriter blockWriter(DbOptions o) { return defaults.blockWriter(o); }
            @Override public BlockReader blockReader(DbOptions o) { return defaults.blockReader(o); }
            @Override public QueryEngine queryEngine(DbOptions o) { return defaults.queryEngine(o); }
            @Override public CompactionPlanner compactionPlanner(DbOptions o) { return defaults.compactionPlanner(o); }
            @Override public RetentionPolicy retentionPolicy(DbOptions o) { return defaults.retentionPolicy(o); }
            @Override public Wal openWal(DbOptions o) {
                Wal delegate = defaults.openWal(o);
                return new Wal() {
                    @Override public WalPosition append(WalRecord r) { return delegate.append(r); }
                    @Override public void replay(Consumer<WalRecord> h) { delegate.replay(h); }
                    @Override public void truncateBefore(long ts) { delegate.truncateBefore(ts); }
                    @Override public Stream<WalEntry> readFrom(WalPosition p) { return delegate.readFrom(p); }
                    @Override public void sync() {
                        events.add("sync");
                        if (failSync.get()) {
                            throw new UncheckedIOException(new IOException("fsync failed"));
                        }
                        delegate.sync();
                    }
                    @Override public void close() { delegate.close(); }
                };
            }
        });
    }

    private static ReplicationTransport transport(Consumer<ReplicationProgress> ack) {
        return (id, after) -> new ReplicationTransport.Session() {
            @Override public Iterator<WalEntry> entries() {
                Context context = Context.current();
                Iterator<WalEntry> records = List.of(new WalEntry(new WalPosition(7, 10),
                        new WalRecord.SeriesCreated(42, Labels.of("cpu_usage")))).iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() {
                        if (records.hasNext()) return true;
                        CountDownLatch cancelled = new CountDownLatch(1);
                        context.addListener(ignored -> cancelled.countDown(), Runnable::run);
                        try {
                            cancelled.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }
                    @Override public WalEntry next() { return records.next(); }
                };
            }
            @Override public void ack(ReplicationProgress progress) { ack.accept(progress); }
            @Override public void close() {}
        };
    }
}
