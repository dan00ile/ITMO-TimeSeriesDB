package org.tsdb.model;

import java.util.List;
import java.util.Objects;

// Одна серия ответа. Без агрегации labels, полные лейблы серии;
// с агрегацией только лейблы из groupBy
public record SeriesResult(Labels labels, List<Sample> samples) {
    public SeriesResult {
        Objects.requireNonNull(labels, "labels");
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
    }
}
