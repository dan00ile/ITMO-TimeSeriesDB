package org.tsdb.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.tsdb.db.DbOptions;
import org.tsdb.db.DbPaths;
import org.tsdb.db.DbStats;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;

// Бенчмарк из lab2 §8: 1000 серий × шаг 15 с × 24 ч = 5.76M точек.
// Меряет скорость записи, размер на диске и время запроса «avg by host за последний час по 100 сериям».
// Сценарий прогоняется warmup раз для прогрева JIT, цифры печатаются по последнему прогону.
//
//   ./gradlew bench                                   — параметры по умолчанию
//   ./gradlew bench -PbenchArgs="hours=2 warmup=0"    — key=value: hosts, cores, hours, stepMs, warmup, queries
public final class Bench {
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long START = 1_760_000_000_000L; // фиксированное «сейчас» — повторяемые прогоны

    private Bench() {
    }

    record Config(int hosts, int cores, int hours, long stepMs, int warmup, int queries) {
        static Config parse(String[] args) {
            Map<String, String> kv = new HashMap<>();
            for (String arg : args) {
                String[] p = arg.split("=", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("expected key=value, got " + arg);
                }
                kv.put(p[0], p[1]);
            }
            Config c = new Config(
                    Integer.parseInt(kv.getOrDefault("hosts", "250")),
                    Integer.parseInt(kv.getOrDefault("cores", "4")),
                    Integer.parseInt(kv.getOrDefault("hours", "24")),
                    Long.parseLong(kv.getOrDefault("stepMs", "15000")),
                    Integer.parseInt(kv.getOrDefault("warmup", "2")),
                    Integer.parseInt(kv.getOrDefault("queries", "50")));
            kv.keySet().removeAll(List.of("hosts", "cores", "hours", "stepMs", "warmup", "queries"));
            if (!kv.isEmpty()) {
                throw new IllegalArgumentException("unknown parameters: " + kv.keySet());
            }
            return c;
        }

        int series() {
            return hosts * cores;
        }

        long ticks() {
            return hours * HOUR / stepMs;
        }
    }

    record Result(long points, double writeSeconds, DbStats stats, long blockBytes, double queryMedianMs,
                  int querySeries) {
    }

    public static void main(String[] args) throws IOException {
        Config cfg = Config.parse(args);
        System.out.printf("Bench: %d series × step %d s × %d h = %,d points, warmup %d%n",
                cfg.series(), cfg.stepMs() / 1000, cfg.hours(), cfg.series() * cfg.ticks(), cfg.warmup());
        Result result = null;
        for (int run = 0; run <= cfg.warmup(); run++) {
            Path dir = Files.createTempDirectory("tsdb-bench");
            try {
                result = runOnce(cfg, dir);
            } finally {
                deleteRecursively(dir);
            }
            System.out.printf("  %s %d: write %.1f s, query %.2f ms%n",
                    run < cfg.warmup() ? "warmup" : "run", run + 1, result.writeSeconds(), result.queryMedianMs());
        }
        print(result);
    }

    static Result runOnce(Config cfg, Path dir) {
        DbOptions options = DbOptions.builder(dir)
                .compactionIntervalMs(0) // сброс и компакция — вручную, чтобы замер был повторяемым
                .build();
        MetricsGenerator gen = new MetricsGenerator(cfg.hosts(), cfg.cores(), START, cfg.stepMs(), 42);
        long ticksPerBlock = options.blockRangeMs() / cfg.stepMs();
        try (TimeSeriesDB db = TimeSeriesDB.open(options)) {
            long t0 = System.nanoTime();
            for (long tick = 0; tick < cfg.ticks(); tick++) {
                db.appendBatch(gen.nextTick());
                if ((tick + 1) % ticksPerBlock == 0) {
                    db.flush(); // как фоновый сброс head раз в окно блока
                }
            }
            db.flush();
            db.compact();
            double writeSeconds = (System.nanoTime() - t0) / 1e9;

            long end = gen.nextTimestamp() - 1;
            int queryHosts = Math.min(cfg.hosts(), Math.max(1, 100 / cfg.cores()));
            Query query = Query.range(List.of(
                            new Matcher.Eq("__name__", "cpu_usage"),
                            new Matcher.Regex("host", hostRegex(queryHosts))),
                    end - HOUR + 1, end).aggregate(Aggregation.AVG, List.of("host"), 0);
            double[] times = new double[cfg.queries()];
            for (int i = 0; i < times.length; i++) {
                long q0 = System.nanoTime();
                int groups = db.query(query).series().size();
                times[i] = (System.nanoTime() - q0) / 1e6;
                if (groups != queryHosts) {
                    throw new IllegalStateException("query returned " + groups + " hosts, expected " + queryHosts);
                }
            }
            Arrays.sort(times);
            return new Result(cfg.series() * cfg.ticks(), writeSeconds, db.stats(),
                    sizeOf(DbPaths.of(options).blocksDir()), times[times.length / 2], queryHosts * cfg.cores());
        }
    }

    // host-0000 … host-(n-1) одним regex'ом через перечисление.
    static String hostRegex(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : "|").append(MetricsGenerator.host(i));
        }
        return sb.toString();
    }

    private static void print(Result r) {
        double rawBytes = r.points() * 16.0; // long timestamp + double value
        System.out.println();
        System.out.printf("points written      %,d%n", r.points());
        System.out.printf("write throughput    %,.0f points/s (%.1f s, включая flush и compact)%n",
                r.points() / r.writeSeconds(), r.writeSeconds());
        System.out.printf("blocks on disk      %d, %,d bytes = %.2f bytes/point (сырые данные — 16)%n",
                r.stats().numBlocks(), r.blockBytes(), r.blockBytes() / (double) r.points());
        System.out.printf("data dir total      %,d bytes (с WAL и индексами), сжатие %.1fx%n",
                r.stats().diskBytes(), rawBytes / r.stats().diskBytes());
        System.out.printf("query avg by host   %.2f ms медиана (последний час, %d серий)%n",
                r.queryMedianMs(), r.querySeries());
    }

    static long sizeOf(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            long total = 0;
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                total += Files.size(f);
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
