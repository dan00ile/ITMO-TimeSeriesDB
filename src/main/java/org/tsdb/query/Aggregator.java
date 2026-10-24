package org.tsdb.query;

import org.tsdb.model.Aggregation;

// Накопитель для одной группы и одного окна, reset() перед новым окном. Не потокобезопасен.
// result() без единого add: SUM и COUNT — 0, AVG/MIN/MAX — NaN.
public interface Aggregator {
    void add(double value);

    double result();

    void reset();

    // IllegalArgumentException для Aggregation#NONE
    static Aggregator of(Aggregation aggregation) {
        return switch (aggregation) {
            case SUM -> new Aggregators.Sum();
            case AVG -> new Aggregators.Avg();
            case MIN -> new Aggregators.Min();
            case MAX -> new Aggregators.Max();
            case COUNT -> new Aggregators.Count();
            case NONE -> throw new IllegalArgumentException("no aggregator for NONE");
        };
    }
}
