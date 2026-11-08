package org.tsdb.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import org.tsdb.db.DbOptions;
import org.tsdb.db.SampleInput;
import org.tsdb.db.TimeSeriesDB;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;

// Общие тестовые данные и хелперы для всех трёх зон. Владелец — C.
public final class Fixtures {

    public static final long STEP_15S = 15_000;

    private Fixtures() {
    }

    // Окна в секундах вместо часов и маленькие chunk'и, чтобы сброс в блок происходил в тесте.
    // Фоновая компакция выключена: иначе ассерты соревнуются с планировщиком.
    public static DbOptions testOptions(Path dataDir) {
        return DbOptions.builder(dataDir)
                .blockRangeMs(1_000)
                .retentionMs(10_000)
                .walSyncIntervalMs(0)
                .chunkMaxSamples(4)
                .compactionIntervalMs(0)
                .build();
    }

    public static Labels cpu(String host) {
        return Labels.of("cpu_usage", "host", host);
    }

    // Лейблы группы в агрегированном ответе: там нет __name__, только groupBy.
    public static Labels group(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of name/value arguments");
        }
        List<Label> labels = new ArrayList<>(nameValuePairs.length / 2);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            labels.add(new Label(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Labels(labels);
    }

    public static List<Sample> regular(long start, long stepMs, int count, LongToDoubleFunction valueAt) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0: " + count);
        }
        List<Sample> samples = new ArrayList<>(count);
        for (long i = 0; i < count; i++) {
            samples.add(new Sample(start + i * stepMs, valueAt.applyAsDouble(i)));
        }
        return List.copyOf(samples);
    }

    // Ряд с постоянным шагом и детерминированными значениями — то, что Gorilla сжимает лучше всего.
    public static List<Sample> regular15s(long start, int count) {
        return regular(start, STEP_15S, count, i -> 0.5 + (i % 7) * 0.01);
    }

    public static List<SampleInput> batch(Labels labels, List<Sample> samples) {
        return samples.stream().map(s -> new SampleInput(labels, s.timestamp(), s.value())).toList();
    }

    public static void appendAll(TimeSeriesDB db, Labels labels, List<Sample> samples) {
        for (Sample s : samples) {
            db.append(labels, s.timestamp(), s.value());
        }
    }

    // Серию ищем по лейблам: id серии снаружи не виден и в разных источниках разный.
    public static List<Sample> samplesOf(QueryResult result, Labels labels) {
        for (SeriesResult series : result.series()) {
            if (series.labels().equals(labels)) {
                return series.samples();
            }
        }
        throw new AssertionError("no series " + labels.canonical() + " in " + result.series().stream()
                .map(s -> s.labels().canonical()).toList());
    }
}
