package org.tsdb.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.cluster.FollowerLoopTest.TestTransport;
import org.tsdb.db.DbOptions;
import org.tsdb.db.LocalTimeSeriesDB;
import org.tsdb.model.Labels;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

/**
 * Интеграционные тесты StoredFollowerCheckpoint: реальный ReplicationStateStore
 * поверх реальной LocalTimeSeriesDB в одном dataDir.
 */
class StoredFollowerCheckpointTest {
    @TempDir Path dir;
    private static final Labels CPU = Labels.of("cpu_usage", "host", "n2");
    private static final WalEntry SERIES = entry(10, new WalRecord.SeriesCreated(42, CPU));
    private static final WalEntry SAMPLE = entry(20,
            new WalRecord.Samples(List.of(new SampleEntry(42, 1000, 0.5))));

    @Test
    void missingStateGivesEmptyProgress() throws Exception {
        try (LocalTimeSeriesDB db = open()) {
            StoredFollowerCheckpoint checkpoint = new StoredFollowerCheckpoint(db);

            assertEquals(ReplicationProgress.empty(), checkpoint.load());
        }
    }

    @Test
    void existingStateKeepsLeaderPositionAndRecoversMaxTsFromHead() throws Exception {
        new ReplicationStateStore(dir).save(new WalPosition(7, 20));
        try (LocalTimeSeriesDB db = open()) {
            db.append(CPU, 1000, 0.5);
            db.append(CPU, 2000, 0.75);

            StoredFollowerCheckpoint checkpoint = new StoredFollowerCheckpoint(db);
            ReplicationProgress loaded = checkpoint.load();

            // Позиция лидера из файла, а не позиция локального WAL.
            assertEquals(new WalPosition(7, 20), loaded.applied());
            assertEquals(2000, loaded.appliedMaxTs());

            checkpoint.save(new ReplicationProgress(new WalPosition(8, 5), 2000));
            assertEquals(Optional.of(new WalPosition(8, 5)), new ReplicationStateStore(dir).load());
        }
    }

    @Test
    void flushedSamplesSurviveReopenAndMaxTsComesFromBlocksNotWal() throws Exception {
        new ReplicationStateStore(dir).save(new WalPosition(7, 20));
        try (LocalTimeSeriesDB db = open()) {
            db.append(CPU, 1000, 0.5);
            db.append(CPU, 2000, 0.75);
            db.flush();
            assertEquals(0, db.stats().headSamples());
            assertEquals(1, db.stats().numBlocks());
        }
        try (LocalTimeSeriesDB db = open()) {
            // WAL после flush не содержит точек: replay ничего не вернул в head,
            // данные живут только в блоках — maxTs из WAL не вычислить.
            assertEquals(0, db.stats().headSamples());
            assertEquals(1, db.stats().numBlocks());

            ReplicationProgress loaded = new StoredFollowerCheckpoint(db).load();

            assertEquals(new WalPosition(7, 20), loaded.applied());
            assertEquals(2000, loaded.appliedMaxTs());
        }
    }

    @Test
    void defaultLoopSavesLeaderPositionToStateFileAndResumesAfterReopen() throws Exception {
        TestTransport transport = new TestTransport(List.of(SERIES, SAMPLE), false);
        try (LocalTimeSeriesDB db = open();
             FollowerLoop loop = new FollowerLoop("n2", db, transport)) {
            loop.start();
            FollowerLoopTest.await(() ->
                    transport.acks.stream().anyMatch(p -> SAMPLE.position().equals(p.applied())));
        }
        // Позиция лидера (сегмент 7), а не позиции локального WAL фолловера.
        WalPosition durable = new ReplicationStateStore(dir).load().orElseThrow();
        assertEquals(new WalPosition(7, 20), durable);

        WalEntry next = entry(30,
                new WalRecord.Samples(List.of(new SampleEntry(42, 2000, 0.75))));
        TestTransport resume = new TestTransport(List.of(next), false);
        try (LocalTimeSeriesDB db = open();
             FollowerLoop loop = new FollowerLoop("n2", db, resume)) {
            loop.start();
            FollowerLoopTest.await(() -> next.position().equals(loop.progress().applied()));

            assertEquals(new WalPosition(7, 20), resume.afters.get(0),
                    "must resume after last durable leader position");
            assertEquals(2, db.stats().headSamples());
            assertEquals(2000, loop.progress().appliedMaxTs());
        }
    }

    private LocalTimeSeriesDB open() {
        return LocalTimeSeriesDB.open(DbOptions.builder(dir).walSyncIntervalMs(0)
                .compactionIntervalMs(0).build());
    }

    private static WalEntry entry(long offset, WalRecord record) {
        return new WalEntry(new WalPosition(7, offset), record);
    }
}
