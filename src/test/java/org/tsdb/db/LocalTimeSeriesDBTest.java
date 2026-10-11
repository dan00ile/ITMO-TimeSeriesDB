package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.testing.Fixtures.appendAll;
import static org.tsdb.testing.Fixtures.batch;
import static org.tsdb.testing.Fixtures.cpu;
import static org.tsdb.testing.Fixtures.regular;
import static org.tsdb.testing.Fixtures.regular15s;
import static org.tsdb.testing.Fixtures.samplesOf;
import static org.tsdb.testing.Fixtures.testOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.block.Block;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;
import org.tsdb.testing.FakeBlocks;
import org.tsdb.testing.FakeComponents;
import org.tsdb.testing.FakeWal;
import org.tsdb.wal.CorruptedWalException;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.WalRecord;

// Фасад поверх слоёв-заглушек из FakeComponents: порядок «журнал, потом память», восстановление,
// жизнь блоков на диске. Сами слои здесь не проверяются — это тесты A и B.
class LocalTimeSeriesDBTest {

    private static final List<Matcher> CPU = List.of(new Matcher.Eq(Labels.METRIC_NAME, "cpu_usage"));
    private static final Query ALL = Query.range(CPU, 0, 1_000_000_000);

    @TempDir
    Path dir;

    @Test
    void appendThenQueryReadsFromHead() {
        List<Sample> samples = regular15s(1_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), samples);

            assertEquals(samples, samplesOf(db.query(ALL), cpu("web-1")));
            assertEquals(List.of(), db.query(Query.range(CPU, 0, 999)).series());
        }
    }

    @Test
    void newSeriesGoesToWalBeforeItsSamples() {
        FakeComponents components = new FakeComponents();
        try (LocalTimeSeriesDB db = open(components)) {
            db.append(cpu("web-1"), 1_000, 0.5);
            db.append(cpu("web-1"), 2_000, 0.6);

            assertEquals(List.of(
                    new WalRecord.SeriesCreated(1, cpu("web-1")),
                    new WalRecord.Samples(List.of(new SampleEntry(1, 1_000, 0.5))),
                    new WalRecord.Samples(List.of(new SampleEntry(1, 2_000, 0.6)))), walRecords());
            assertEquals(2, components.wal().syncs(), "walSyncIntervalMs = 0: fsync на каждый append");
        }
    }

    @Test
    void batchIsOneWalRecordAndOneSync() {
        FakeComponents components = new FakeComponents();
        try (LocalTimeSeriesDB db = open(components)) {
            db.appendBatch(batch(cpu("web-1"), regular15s(1_000, 5)));

            assertEquals(2, walRecords().size(), "SeriesCreated + одна пачка Samples");
            assertEquals(1, components.wal().syncs());
        }
    }

    @Test
    void dataSurvivesRestartViaWal() {
        List<Sample> web1 = regular15s(1_000, 10);
        List<Sample> web2 = regular15s(2_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), web1);
            appendAll(db, cpu("web-2"), web2);
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(web1, samplesOf(db.query(ALL), cpu("web-1")));
            assertEquals(web2, samplesOf(db.query(ALL), cpu("web-2")));

            db.append(cpu("web-3"), 1_000, 1.0);
            assertEquals(3, db.stats().numSeries());
        }
    }

    @Test
    void rejectedSampleNeverReachesWal() {
        try (LocalTimeSeriesDB db = open()) {
            db.append(cpu("web-1"), 1_000, 1.0);

            assertThrows(OutOfOrderSampleException.class, () -> db.append(cpu("web-1"), 1_000, 2.0));
            assertThrows(OutOfOrderSampleException.class, () -> db.append(cpu("web-1"), 500, 2.0));
            assertEquals(2, walRecords().size());
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(List.of(new Sample(1_000, 1.0)), samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void batchKeepsSamplesBeforeTheBadOne() {
        List<SampleInput> batch = List.of(
                new SampleInput(cpu("web-1"), 1_000, 1.0),
                new SampleInput(cpu("web-2"), 1_000, 2.0),
                new SampleInput(cpu("web-1"), 500, 3.0),
                new SampleInput(cpu("web-3"), 1_000, 4.0));
        List<Labels> written = List.of(cpu("web-1"), cpu("web-2"));
        try (LocalTimeSeriesDB db = open()) {
            assertThrows(OutOfOrderSampleException.class, () -> db.appendBatch(batch));

            assertEquals(written, labelsOf(db.query(ALL)));
            assertEquals(Set.of("web-1", "web-2"), db.labelValues("host"));
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(written, labelsOf(db.query(ALL)));
        }
    }

    @Test
    void seriesWithoutMetricNameRejected() {
        try (LocalTimeSeriesDB db = open()) {
            Labels noName = new Labels(List.of(new Label("host", "web-1")));

            assertThrows(IllegalArgumentException.class, () -> db.append(noName, 1_000, 1.0));
            assertEquals(Set.of(), db.labelNames());
            assertEquals(List.of(), walRecords());
        }
    }

    @Test
    void flushWritesOneBlockAndTruncatesWal() throws IOException {
        List<Sample> samples = regular15s(1_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), samples);
            db.flush();

            assertEquals(1, entries(paths().blocksDir()).size());
            assertEquals(List.of(), entries(paths().tmpDir()));
            assertEquals(0, db.stats().headSamples());
            assertTrue(walRecords().stream().noneMatch(r -> r instanceof WalRecord.Samples));
            assertEquals(samples, samplesOf(db.query(ALL), cpu("web-1")));

            db.flush();
            assertEquals(1, db.stats().numBlocks(), "пустой head блок не создаёт");
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(samples, samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void sampleNotNewerThanLastBlockRejected() {
        try (LocalTimeSeriesDB db = open()) {
            db.append(cpu("web-1"), 1_000, 1.0);
            db.append(cpu("web-1"), 2_000, 1.0);
            db.flush();

            // у web-2 своих точек нет, но время до 2_000 уже лежит в блоке
            assertThrows(OutOfOrderSampleException.class, () -> db.append(cpu("web-2"), 1_500, 1.0));
            assertEquals(Set.of("web-1"), db.labelValues("host"));
            db.append(cpu("web-2"), 2_500, 1.0);
        }
    }

    @Test
    void replaySkipsSamplesAlreadyInBlock() {
        List<Sample> samples = regular15s(1_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), samples);
            List<WalRecord> beforeFlush = List.copyOf(walRecords());
            db.flush();
            // упали между записью блока и обрезкой журнала
            walRecords().clear();
            walRecords().addAll(beforeFlush);
        }
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(samples, samplesOf(db.query(ALL), cpu("web-1")));
            assertEquals(0, db.stats().headSamples());
        }
    }

    @Test
    void walWithUnknownSeriesFailsOpenAndClosesWal() {
        walRecords().add(new WalRecord.Samples(List.of(new SampleEntry(7, 1_000, 1.0))));
        FakeComponents components = new FakeComponents();

        assertThrows(CorruptedWalException.class, () -> open(components));
        assertTrue(components.wal().isClosed());
    }

    @Test
    void compactMergesBlocksAndKeepsResults() throws IOException {
        try (LocalTimeSeriesDB db = open()) {
            for (int i = 0; i < 3; i++) {
                appendAll(db, cpu("web-1"), regular15s(1_000 + i * 1_000_000L, 10));
                db.flush();
            }
            QueryResult before = db.query(ALL);
            db.compact();

            assertEquals(1, db.stats().numBlocks());
            assertEquals(1, entries(paths().blocksDir()).size());
            assertEquals(before, db.query(ALL));
        }
    }

    @Test
    void deleteBeforeDropsOnlyOldBlocks() throws IOException {
        List<Sample> recent = regular15s(1_000_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), regular15s(1_000, 10));
            db.flush();
            appendAll(db, cpu("web-1"), recent);
            db.flush();

            db.deleteBefore(500_000);

            assertEquals(1, entries(paths().blocksDir()).size());
            assertEquals(recent, samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void openCleansUpAfterCrash() throws IOException {
        List<Sample> first = regular15s(1_000, 10);
        List<Sample> second = regular15s(1_000_000, 10);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), first);
            db.flush();
            appendAll(db, cpu("web-1"), second);
            db.flush();
        }
        // недописанный блок в tmp/ и слитый блок, исходные которого ещё не удалены
        Files.createDirectories(paths().tmpDir().resolve("half-written"));
        FakeBlocks store = new FakeBlocks();
        List<Block> parts = new ArrayList<>();
        for (Path blockDir : entries(paths().blocksDir())) {
            parts.add(store.open(blockDir));
        }
        store.merge(parts, paths().blockDir("MERGED"));

        try (LocalTimeSeriesDB db = open()) {
            assertEquals(List.of(paths().blockDir("MERGED")), entries(paths().blocksDir()));
            assertEquals(List.of(), entries(paths().tmpDir()));
            assertEquals(Stream.concat(first.stream(), second.stream()).toList(),
                    samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void maintenanceMovesOldestWindowToBlock() {
        // окно 1 с, точки каждые 100 мс на 3 с: head покрывает больше полутора окон
        List<Sample> samples = regular(0, 100, 30, i -> i);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), samples);
            db.runMaintenance();

            assertEquals(1, db.stats().numBlocks());
            // chunk'и по 4 точки: [0..300] и [400..700] ушли в блок, [800..1100] пересекает границу 1000
            // и остаётся в head целиком — head режет по chunk'ам, не по точкам
            assertEquals(22, db.stats().headSamples(), "в блок ушли chunk'и окна [0, 1000)");
            assertEquals(samples, samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void maintenanceDropsBlocksOutsideRetention() {
        List<Sample> recent = regular15s(100_000, 4);
        try (LocalTimeSeriesDB db = open()) {
            appendAll(db, cpu("web-1"), regular15s(1_000, 4));
            db.flush();
            appendAll(db, cpu("web-1"), recent);
            db.flush();

            db.runMaintenance();

            assertEquals(1, db.stats().numBlocks(), "retention 10 с считается от самой свежей точки");
            assertEquals(recent, samplesOf(db.query(ALL), cpu("web-1")));
        }
    }

    @Test
    void statsAndLabelsCoverHeadAndBlocks() {
        try (LocalTimeSeriesDB db = open()) {
            assertEquals(new DbStats(0, 0, 0, 0), db.stats());

            appendAll(db, cpu("web-1"), regular15s(1_000, 10));
            db.flush();
            appendAll(db, cpu("web-1"), regular15s(1_000_000, 5));
            db.append(Labels.of("mem_usage", "host", "db-1"), 1_000_000, 0.3);

            DbStats stats = db.stats();
            assertEquals(2, stats.numSeries(), "web-1 есть и в блоке, и в head — это одна серия");
            assertEquals(1, stats.numBlocks());
            assertEquals(6, stats.headSamples());
            assertTrue(stats.diskBytes() > 0);
            assertEquals(Set.of(Labels.METRIC_NAME, "host"), db.labelNames());
            assertEquals(Set.of("cpu_usage", "mem_usage"), db.labelValues(Labels.METRIC_NAME));
        }
    }

    @Test
    void closeIsIdempotentAndRejectsLaterCalls() {
        FakeComponents components = new FakeComponents();
        LocalTimeSeriesDB db = open(components);
        db.close();
        db.close();

        assertTrue(components.wal().isClosed());
        assertThrows(IllegalStateException.class, () -> db.append(cpu("web-1"), 1_000, 1.0));
        assertThrows(IllegalStateException.class, () -> db.query(ALL));
        assertThrows(IllegalStateException.class, db::flush);
    }

    @Test
    void walIsSyncedInBackground() throws InterruptedException {
        FakeComponents components = new FakeComponents();
        DbOptions options = DbOptions.builder(dir).walSyncIntervalMs(10).compactionIntervalMs(0).build();
        try (LocalTimeSeriesDB db = LocalTimeSeriesDB.open(options, components)) {
            db.append(cpu("web-1"), 1_000, 1.0);

            long deadline = System.currentTimeMillis() + 5_000;
            while (components.wal().syncs() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(components.wal().syncs() > 0);
        }
    }

    private LocalTimeSeriesDB open() {
        return open(new FakeComponents());
    }

    private LocalTimeSeriesDB open(FakeComponents components) {
        return LocalTimeSeriesDB.open(testOptions(dir), components);
    }

    private DbPaths paths() {
        return DbPaths.of(testOptions(dir));
    }

    private List<WalRecord> walRecords() {
        return FakeWal.records(paths().walDir());
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    private static List<Labels> labelsOf(QueryResult result) {
        return result.series().stream().map(SeriesResult::labels).toList();
    }
}
