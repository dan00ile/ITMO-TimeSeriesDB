package org.tsdb.model;

import java.util.List;
import java.util.Objects;

// Ответ на запрос. Серии отсортированы по Labels.canonical(), пустых серий нет.
public record QueryResult(List<SeriesResult> series) {
    public QueryResult {
        series = List.copyOf(Objects.requireNonNull(series, "series"));
    }
}
