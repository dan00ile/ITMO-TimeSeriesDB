package org.tsdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.tsdb.bench.MetricsGenerator;
import org.tsdb.db.DbOptions;
import org.tsdb.db.DbStats;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;

// Демо из lab2 §9: 1000 серий за 4 часа, сброс в блоки, запрос, сжатие.
//   ./gradlew demo                          — во временный каталог
//   ./gradlew demo -PdemoDir=data/demo      — в свой каталог, он должен быть пустым (точки те же, повтор отклонится как out-of-order)
public final class Demo {
    private static final long MINUTE = 60_000;
    private static final long START = 1_760_000_000_000L;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private Demo() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("tsdb-demo");
        System.out.println("data dir: " + dir.toAbsolutePath());
        MetricsGenerator gen = new MetricsGenerator(250, 4, START, 15_000, 42);

        try (TimeSeriesDB db = TimeSeriesDB.open(DbOptions.defaults(dir))) {
            long points = 0;
            for (int tick = 0; tick < 4 * 60 * 4; tick++) { // 4 часа по 15 с
                db.appendBatch(gen.nextTick());
                points += gen.series().size();
            }
            db.flush();
            DbStats stats = db.stats();
            System.out.printf("written %,d points in %d series, %d blocks, %,d bytes on disk%n",
                    points, stats.numSeries(), stats.numBlocks(), stats.diskBytes());
            System.out.printf("%.2f bytes/point vs 16 raw — compression %.1fx%n",
                    stats.diskBytes() / (double) points, points * 16.0 / stats.diskBytes());

            // последний час данных: [end - 60 мин, end), окна по 15 минут; ts точки ответа — начало окна
            long end = gen.nextTimestamp();
            long start = end - 60 * MINUTE;
            Query query = Query.range(List.of(
                            new Matcher.Eq("__name__", "cpu_usage"),
                            new Matcher.Regex("host", "host-000[0-2]")),
                    start, end - 1).aggregate(Aggregation.AVG, List.of("host"), 15 * MINUTE);
            QueryResult result = db.query(query);
            System.out.println();
            System.out.println("avg cpu_usage by host, last hour of data, 15 min windows (UTC):");
            StringBuilder header = new StringBuilder(String.format("  %-22s", ""));
            for (long w = start; w < end; w += 15 * MINUTE) {
                header.append(String.format("  %s–%s", HH_MM.format(Instant.ofEpochMilli(w)),
                        HH_MM.format(Instant.ofEpochMilli(w + 15 * MINUTE))));
            }
            System.out.println(header);
            for (SeriesResult s : result.series()) {
                StringBuilder line = new StringBuilder(String.format("  %-22s", s.labels().canonical()));
                for (Sample p : s.samples()) {
                    line.append(String.format("  %11.3f", p.value()));
                }
                System.out.println(line);
            }
        }
    }
}
