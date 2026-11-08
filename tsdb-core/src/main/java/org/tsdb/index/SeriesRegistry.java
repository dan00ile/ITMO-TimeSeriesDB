package org.tsdb.index;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.tsdb.model.Labels;
import org.tsdb.model.Series;

// Двусторонняя мапа Labels <-> seriesId. Одна на head; у блока — своя, только для чтения.
public interface SeriesRegistry {
    // Id серии; новая серия получает следующий id.
    long getOrCreate(Labels labels);

    OptionalLong get(Labels labels);

    Optional<Labels> labels(long id);

    int size();

    Stream<Series> all();
}
