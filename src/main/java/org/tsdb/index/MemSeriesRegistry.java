package org.tsdb.index;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.tsdb.model.Labels;
import org.tsdb.model.Series;

// In-memory registry для head
public final class MemSeriesRegistry implements SeriesRegistry {
    // Зарегистрировать серию с заданным id: replay WAL (SeriesCreated, checkpoint) и репликация.
    // Идемпотентный: повтор с теми же labels — no-op; id или labels уже заняты другой парой — IllegalStateException.
    // После restore следующий id из getOrCreate = max(id) + 1.
    public void restore(long id, Labels labels) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public long getOrCreate(Labels labels) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public OptionalLong get(Labels labels) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Optional<Labels> labels(long id) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Stream<Series> all() {
        throw new UnsupportedOperationException("lab2");
    }
}
