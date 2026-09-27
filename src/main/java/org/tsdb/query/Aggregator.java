package org.tsdb.query;

import org.tsdb.model.Aggregation;

// Накопитель для одной группы и одного окна, reset() перед новым окном.
public interface Aggregator {
    void add(double value);

    double result();

    void reset();

    // IllegalArgumentException для Aggregation#NONE
    static Aggregator of(Aggregation aggregation) {
        throw new UnsupportedOperationException("lab2");
    }
}
