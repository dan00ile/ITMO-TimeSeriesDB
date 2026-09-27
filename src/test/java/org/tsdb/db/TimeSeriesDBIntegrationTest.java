package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.testing.Fixtures.appendAll;
import static org.tsdb.testing.Fixtures.batch;
import static org.tsdb.testing.Fixtures.cpu;
import static org.tsdb.testing.Fixtures.regular15s;
import static org.tsdb.testing.Fixtures.samplesOf;
import static org.tsdb.testing.Fixtures.testOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;

/**
 * End-to-end сценарии через фасад — зона C. В отличие от TimeSeriesDBContractTest здесь
 * проходы через все слои сразу. flush/compact/deleteBefore вызываем руками, на фоновые
 * задачи не полагаемся.
 */
class TimeSeriesDBIntegrationTest {

    private static final List<Matcher> CPU = List.of(new Matcher.Eq(Labels.METRIC_NAME, "cpu_usage"));
    private static final List<String> HOSTS = List.of("web-1", "web-2", "db-1");

    @TempDir
    Path dir;

    @Test
    @Disabled("lab2")
    @DisplayName("data written across several blocks is read back as one series")
    void seriesIsStitchedFromBlocksAndHead() {
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            Labels labels = cpu("web-1");
            List<Sample> first = regular15s(1_000, 20);
            List<Sample> second = regular15s(1_000 + 20 * 15_000, 20);

            appendAll(db, labels, first);
            db.flush();
            appendAll(db, labels, second);

            assertTrue(db.stats().numBlocks() >= 1);
            assertTrue(db.stats().headSamples() > 0);

            QueryResult res = db.query(Query.range(CPU, 0, Long.MAX_VALUE / 2));
            assertEquals(Stream.concat(first.stream(), second.stream()).toList(), samplesOf(res, labels));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("compaction merges blocks without changing query results")
    void compactionDoesNotChangeQueryResults() {
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            for (String host : HOSTS) {
                appendAll(db, cpu(host), regular15s(1_000, 40));
            }
            db.flush();

            Query query = Query.range(CPU, 0, 1_000_000);
            QueryResult before = db.query(query);
            db.compact();

            assertEquals(before, db.query(query));
            assertEquals(HOSTS.size(), db.stats().numSeries());
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("deleteBefore drops old blocks and keeps recent data")
    void deleteBeforeDropsOldBlocksOnly() {
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            Labels labels = cpu("web-1");
            List<Sample> old = regular15s(1_000, 10);
            List<Sample> recent = regular15s(1_000_000, 10);

            appendAll(db, labels, old);
            db.flush();
            appendAll(db, labels, recent);
            db.flush();

            db.deleteBefore(500_000);

            assertEquals(recent, samplesOf(db.query(Query.range(CPU, 0, 2_000_000)), labels));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("appendBatch is equivalent to append in a loop")
    void appendBatchEqualsIndividualAppends() {
        Labels labels = cpu("web-1");
        List<Sample> samples = regular15s(1_000, 30);
        Query query = Query.range(CPU, 0, 1_000_000);

        try (TimeSeriesDB a = TimeSeriesDB.open(testOptions(dir.resolve("batched")));
             TimeSeriesDB b = TimeSeriesDB.open(testOptions(dir.resolve("single")))) {
            a.appendBatch(batch(labels, samples));
            appendAll(b, labels, samples);

            assertEquals(a.query(query), b.query(query));
            assertEquals(samples, samplesOf(a.query(query), labels));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("avg by host over a range spanning blocks and head")
    void avgByHostAcrossBlocksAndHead() {
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            for (String host : HOSTS) {
                appendAll(db, cpu(host), regular15s(1_000, 20));
            }
            db.flush();
            for (String host : HOSTS) {
                appendAll(db, cpu(host), regular15s(1_000 + 20 * 15_000, 20));
            }

            QueryResult res = db.query(
                    Query.range(CPU, 0, 1_000_000).aggregate(Aggregation.AVG, List.of("host"), 0));

            assertEquals(HOSTS.size(), res.series().size());
            for (var series : res.series()) {
                assertEquals(1, series.samples().size(), "без step группа сворачивается в одну точку");
            }
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("concurrent appends from many threads lose nothing")
    void concurrentAppendsFromManyThreads() throws Exception {
        int perSeries = 200;
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir));
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {

            List<Future<Void>> tasks = HOSTS.stream()
                    .map(host -> pool.<Void>submit(() -> {
                        appendAll(db, cpu(host), regular15s(1_000, perSeries));
                        return null;
                    }))
                    .toList();
            for (Future<Void> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }

            QueryResult res = db.query(Query.range(CPU, 0, 1_000_000_000));
            assertEquals(HOSTS.size(), res.series().size());
            for (String host : HOSTS) {
                assertEquals(perSeries, samplesOf(res, cpu(host)).size());
            }
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("stats reflect what was written")
    void statsReflectWrittenData() {
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            assertEquals(new DbStats(0, 0, 0, db.stats().diskBytes()), db.stats());

            for (String host : HOSTS) {
                appendAll(db, cpu(host), regular15s(1_000, 10));
            }
            assertEquals(HOSTS.size(), db.stats().numSeries());
            assertEquals(HOSTS.size() * 10L, db.stats().headSamples());

            db.flush();
            assertEquals(0, db.stats().headSamples());
            assertTrue(db.stats().numBlocks() >= 1);
            assertTrue(db.stats().diskBytes() > 0);
        }
    }
}
