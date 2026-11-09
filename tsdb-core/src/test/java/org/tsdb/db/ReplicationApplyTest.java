package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.testing.Fixtures.cpu;
import static org.tsdb.testing.Fixtures.samplesOf;
import static org.tsdb.testing.Fixtures.testOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.Sample;
import org.tsdb.wal.CorruptedWalException;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

// Сторона фолловера: applyRecord применяет записи лидера с теми же id серий и не трогает
// собственный WAL — фолловер дописывает его сам. Настоящие FileWal/MemHead, без фоновых задач.
class ReplicationApplyTest {

    private static final List<Matcher> CPU = List.of(new Matcher.Eq(Labels.METRIC_NAME, "cpu_usage"));
    private static final Query ALL = Query.range(CPU, 0, 1_000_000_000);

    @TempDir
    Path dir;

    @Test
    void followerAppliesLeaderRecordsWithExactIds() {
        try (LocalTimeSeriesDB leader = open(dir.resolve("leader"));
             LocalTimeSeriesDB follower = open(dir.resolve("follower"))) {
            leader.appendBatch(List.of(
                    new SampleInput(cpu("web-1"), 2_000, 1.0),
                    new SampleInput(cpu("web-2"), 2_000, 2.0)));
            List<WalRecord> records = walRecords(leader);
            for (WalRecord record : records) {
                follower.wal().append(record);
                follower.applyRecord(record);
            }

            assertEquals(List.of(new Sample(2_000, 1.0)), samplesOf(follower.query(ALL), cpu("web-1")));
            assertEquals(List.of(new Sample(2_000, 2.0)), samplesOf(follower.query(ALL), cpu("web-2")));

            // локальная запись в ту же серию уходит в WAL фолловера с id лидера, а не своим
            long leaderId = sampleEntries(records).get(0).seriesId();
            follower.append(cpu("web-1"), 3_000, 1.5);
            assertTrue(sampleEntries(walRecords(follower)).stream()
                            .anyMatch(e -> e.seriesId() == leaderId && e.timestamp() == 3_000),
                    "локальная точка применённой серии несёт id лидера");
        }
    }

    // разреженные id из потока лидера сохраняются точно, следующий — max + 1, и после рестарта тоже
    @Test
    void sparseIdsRestoredExactlyAndNextIsMaxPlusOneAfterRestart() {
        WalRecord five = new WalRecord.SeriesCreated(5, cpu("web-1"));
        WalRecord nine = new WalRecord.SeriesCreated(9, cpu("web-2"));
        try (LocalTimeSeriesDB db = open(dir)) {
            db.wal().append(five);
            db.applyRecord(five);
            db.wal().append(nine);
            db.applyRecord(nine);
            db.append(cpu("web-3"), 2_000, 3.0);
        }
        try (LocalTimeSeriesDB db = open(dir)) {
            db.append(cpu("web-4"), 3_000, 4.0);

            List<Long> ids = seriesIds(walRecords(db));
            assertTrue(ids.containsAll(List.of(5L, 9L, 10L, 11L)),
                    "id лидера сохранены точно, новые — 10 и 11, а не 1..4: " + ids);
        }
    }

    @Test
    void replicatedDataSurvivesRestartWhenCallerAppendsToWal() {
        WalRecord created = new WalRecord.SeriesCreated(1, cpu("web-1"));
        WalRecord samples = new WalRecord.Samples(List.of(new SampleEntry(1, 2_000, 1.0)));
        try (LocalTimeSeriesDB db = open(dir)) {
            db.wal().append(created);
            db.applyRecord(created);
            db.wal().append(samples);
            db.applyRecord(samples);
        }
        try (LocalTimeSeriesDB db = open(dir)) {
            assertEquals(List.of(new Sample(2_000, 1.0)), samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void applyRecordDoesNotAppendToWal() {
        try (LocalTimeSeriesDB db = open(dir)) {
            db.applyRecord(new WalRecord.SeriesCreated(1, cpu("web-1")));
            db.applyRecord(new WalRecord.Samples(List.of(new SampleEntry(1, 2_000, 1.0))));

            assertEquals(List.of(), walRecords(db), "память видит точку, WAL пуст");
            assertEquals(List.of(new Sample(2_000, 1.0)), samplesOf(db.query(ALL), cpu("web-1")));
        }
        try (LocalTimeSeriesDB db = open(dir)) {
            assertEquals(List.of(), db.query(ALL).series(), "без записи в WAL после рестарта данных нет");
        }
    }

    @Test
    void duplicateSeriesCreatedIsHarmless() {
        try (LocalTimeSeriesDB db = open(dir)) {
            WalRecord created = new WalRecord.SeriesCreated(3, cpu("web-1"));
            db.applyRecord(created);
            db.applyRecord(created);

            db.applyRecord(new WalRecord.Samples(List.of(new SampleEntry(3, 2_000, 1.0))));
            assertEquals(1, db.stats().numSeries());
            assertEquals(List.of(new Sample(2_000, 1.0)), samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void duplicateAndOutOfOrderSamplesSkipped() {
        try (LocalTimeSeriesDB db = open(dir)) {
            db.applyRecord(new WalRecord.SeriesCreated(3, cpu("web-1")));
            db.applyRecord(new WalRecord.Samples(List.of(new SampleEntry(3, 2_000, 1.0))));
            db.applyRecord(new WalRecord.Samples(List.of(new SampleEntry(3, 2_000, 9.9))));
            db.applyRecord(new WalRecord.Samples(List.of(new SampleEntry(3, 1_000, 9.9))));

            assertEquals(List.of(new Sample(2_000, 1.0)), samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void samplesOfUnknownSeriesRejected() {
        try (LocalTimeSeriesDB db = open(dir)) {
            assertThrows(CorruptedWalException.class, () -> db.applyRecord(
                    new WalRecord.Samples(List.of(new SampleEntry(42, 2_000, 1.0)))));
        }
    }

    @Test
    void conflictingSeriesIdsRejected() {
        try (LocalTimeSeriesDB db = open(dir)) {
            db.applyRecord(new WalRecord.SeriesCreated(5, cpu("web-1")));

            assertThrows(CorruptedWalException.class, () -> db.applyRecord(
                    new WalRecord.SeriesCreated(5, cpu("web-2"))));
            assertThrows(CorruptedWalException.class, () -> db.applyRecord(
                    new WalRecord.SeriesCreated(6, cpu("web-1"))));
            assertEquals(1, db.stats().numSeries());
        }
    }

    @Test
    void nullRecordRejected() {
        try (LocalTimeSeriesDB db = open(dir)) {
            assertThrows(NullPointerException.class, () -> db.applyRecord(null));
        }
    }

    @Test
    void closedDbRejectsApplyRecordAndWal() {
        LocalTimeSeriesDB db = open(dir);
        db.close();

        assertThrows(IllegalStateException.class, () -> db.applyRecord(
                new WalRecord.SeriesCreated(1, cpu("web-1"))));
        assertThrows(IllegalStateException.class, db::wal);
    }

    @Test
    void maxTimestampSurvivesFlushAndRestartForCheckpointRecovery() {
        try (LocalTimeSeriesDB db = open(dir)) {
            assertEquals(Long.MIN_VALUE, db.maxTimestamp());
            db.append(cpu("web-1"), 1000, 1);
            assertEquals(1000, db.maxTimestamp());
            db.flush();
            assertEquals(0, db.stats().headSamples());
            assertEquals(1000, db.maxTimestamp());
        }
        try (LocalTimeSeriesDB db = open(dir)) {
            assertEquals(1000, db.maxTimestamp());
            db.append(cpu("web-2"), 2000, 2);
            assertEquals(2000, db.maxTimestamp());
        }
    }

    private LocalTimeSeriesDB open(Path dataDir) {
        return LocalTimeSeriesDB.open(testOptions(dataDir));
    }

    // Читаем журнал через сам wal(): первая запись свежего WAL — сегмент 1, смещение 0.
    private static List<WalRecord> walRecords(LocalTimeSeriesDB db) {
        try (Stream<WalEntry> entries = db.wal().readFrom(new WalPosition(1, 0))) {
            return entries.map(WalEntry::record).toList();
        }
    }

    private static List<SampleEntry> sampleEntries(List<WalRecord> records) {
        return records.stream()
                .filter(r -> r instanceof WalRecord.Samples)
                .flatMap(r -> ((WalRecord.Samples) r).entries().stream())
                .toList();
    }

    private static List<Long> seriesIds(List<WalRecord> records) {
        return records.stream()
                .flatMap(r -> r instanceof WalRecord.SeriesCreated created
                        ? Stream.of(created.id())
                        : ((WalRecord.Samples) r).entries().stream().map(SampleEntry::seriesId))
                .toList();
    }
}
