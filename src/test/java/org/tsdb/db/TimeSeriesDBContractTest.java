package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.testing.Fixtures.appendAll;
import static org.tsdb.testing.Fixtures.cpu;
import static org.tsdb.testing.Fixtures.group;
import static org.tsdb.testing.Fixtures.regular15s;
import static org.tsdb.testing.Fixtures.samplesOf;
import static org.tsdb.testing.Fixtures.testOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;

// Обещания фасада снаружи, независимо от устройства head, WAL и блоков.
class TimeSeriesDBContractTest {

    private static final List<Matcher> CPU = List.of(new Matcher.Eq(Labels.METRIC_NAME, "cpu_usage"));

    @TempDir
    Path dir;

    @Test
    @Disabled("lab2")
    @DisplayName("append then query returns sample")
    void appendThenQueryReturnsSample() {
        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            Labels labels = cpu("web-1");
            db.append(labels, 1_000, 0.5);

            QueryResult res = db.query(Query.range(CPU, 0, 2_000));

            assertEquals(1, res.series().size());
            assertEquals(List.of(new Sample(1_000, 0.5)), res.series().get(0).samples());
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("query outside the range returns nothing")
    void queryOutsideRangeReturnsEmpty() {
        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            db.append(cpu("web-1"), 1_000, 0.5);

            assertEquals(List.of(), db.query(Query.range(CPU, 2_000, 3_000)).series());
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("data written before a crash comes back from the WAL")
    void dataSurvivesRestartViaWal() {
        Labels labels = cpu("web-1");
        List<Sample> samples = regular15s(1_000, 10);

        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            appendAll(db, labels, samples);
        }
        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            QueryResult res = db.query(Query.range(CPU, 0, 1_000_000));
            assertEquals(samples, samplesOf(res, labels));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("flush moves head to a block on disk and queries still see the data")
    void flushCreatesBlockOnDiskAndQueryStillWorks() throws Exception {
        Labels labels = cpu("web-1");
        List<Sample> samples = regular15s(1_000, 10);

        try (TimeSeriesDB db = TimeSeriesDB.open(testOptions(dir))) {
            appendAll(db, labels, samples);
            db.flush();

            Path blocks = DbPaths.of(testOptions(dir)).blocksDir();
            try (var entries = Files.list(blocks)) {
                assertEquals(1, entries.count(), "flush должен создать ровно один блок");
            }
            assertEquals(1, db.stats().numBlocks());
            assertEquals(0, db.stats().headSamples());
            assertEquals(samples, samplesOf(db.query(Query.range(CPU, 0, 1_000_000)), labels));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("out-of-order sample is rejected")
    void outOfOrderSampleRejected() {
        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            Labels labels = cpu("web-1");
            db.append(labels, 1_000, 1.0);

            assertThrows(OutOfOrderSampleException.class, () -> db.append(labels, 500, 1.0));
            assertThrows(OutOfOrderSampleException.class, () -> db.append(labels, 1_000, 1.0));
            db.append(cpu("web-2"), 500, 1.0);
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("avg by host over a range")
    void avgByHostOverRange() {
        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            db.append(cpu("web-1"), 1_000, 0.40);
            db.append(cpu("web-1"), 2_000, 0.44);
            db.append(cpu("web-2"), 1_000, 0.70);
            db.append(cpu("web-2"), 2_000, 0.80);

            QueryResult res = db.query(
                    Query.range(CPU, 0, 3_000).aggregate(Aggregation.AVG, List.of("host"), 0));

            assertEquals(2, res.series().size());
            assertEquals(0.42, samplesOf(res, group("host", "web-1")).get(0).value(), 1e-9);
            assertEquals(0.75, samplesOf(res, group("host", "web-2")).get(0).value(), 1e-9);
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("labelNames and labelValues list everything appended")
    void labelNamesAndValuesListEverythingAppended() {
        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            db.append(cpu("web-1"), 1_000, 0.5);
            db.append(cpu("web-2"), 1_000, 0.5);

            assertTrue(db.labelNames().containsAll(List.of(Labels.METRIC_NAME, "host")));
            assertEquals(Set.of("web-1", "web-2"), db.labelValues("host"));
            assertEquals(Set.of(), db.labelValues("no_such_label"));
        }
    }

    @Test
    @Disabled("lab2")
    @DisplayName("a closed database rejects further calls")
    void closedDbRejectsCalls() {
        TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir));
        db.close();
        db.close();

        assertThrows(IllegalStateException.class, () -> db.append(cpu("web-1"), 1_000, 0.5));
        assertThrows(IllegalStateException.class, () -> db.query(Query.range(CPU, 0, 1)));
    }
}
