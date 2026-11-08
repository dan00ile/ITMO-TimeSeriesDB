package org.tsdb.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.tsdb.db.SampleInput;
import org.tsdb.model.Labels;

// Синтетические метрики: hosts × cores серий cpu_usage{host="host-0007",core="2"}, по точке на серию раз в stepMs.
// Значение — случайное блуждание в [0, 1] с округлением до 0.01, как у процентов загрузки CPU.
// Seed фиксирован: два прогона с одними параметрами дают одни и те же данные.
public final class MetricsGenerator {
    private final List<Labels> series;
    private final double[] values;
    private final Random random;
    private final long stepMs;
    private long nextTimestamp;

    public MetricsGenerator(int hosts, int cores, long start, long stepMs, long seed) {
        if (hosts <= 0 || cores <= 0 || stepMs <= 0) {
            throw new IllegalArgumentException("hosts, cores and stepMs must be > 0");
        }
        this.random = new Random(seed);
        this.stepMs = stepMs;
        this.nextTimestamp = start;
        List<Labels> list = new ArrayList<>(hosts * cores);
        for (int h = 0; h < hosts; h++) {
            for (int c = 0; c < cores; c++) {
                list.add(Labels.of("cpu_usage", "host", host(h), "core", Integer.toString(c)));
            }
        }
        this.series = List.copyOf(list);
        this.values = new double[series.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = round(random.nextDouble());
        }
    }

    public static String host(int index) {
        return String.format("host-%04d", index);
    }

    public List<Labels> series() {
        return series;
    }

    // Timestamp, который получит следующий nextTick().
    public long nextTimestamp() {
        return nextTimestamp;
    }

    // Одна точка на каждую серию с общим timestamp; дальше время сдвигается на stepMs.
    public List<SampleInput> nextTick() {
        long ts = nextTimestamp;
        nextTimestamp += stepMs;
        List<SampleInput> batch = new ArrayList<>(series.size());
        for (int i = 0; i < values.length; i++) {
            values[i] = round(Math.clamp(values[i] + (random.nextDouble() - 0.5) * 0.04, 0.0, 1.0));
            batch.add(new SampleInput(series.get(i), ts, values[i]));
        }
        return batch;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
