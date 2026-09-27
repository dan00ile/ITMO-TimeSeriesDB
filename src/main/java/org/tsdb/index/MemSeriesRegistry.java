package org.tsdb.index;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.tsdb.model.Labels;
import org.tsdb.model.Series;

// In-memory registry для head
public final class MemSeriesRegistry implements SeriesRegistry {
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
