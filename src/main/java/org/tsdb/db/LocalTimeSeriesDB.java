package org.tsdb.db;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.tsdb.model.Labels;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;

/**
 * Одноузловая БД: связывает слои и держит порядок «WAL раньше памяти». Сама не сжимает,
 * не индексирует и не агрегирует. В лабах 4-5 её обернут Replicator и ShardRouter.
 */
public final class LocalTimeSeriesDB implements TimeSeriesDB {

    private final DbOptions options;
    private final DbPaths paths;

    private LocalTimeSeriesDB(DbOptions options) {
        this.options = options;
        this.paths = DbPaths.of(options);
    }

    // Фабрика, а не конструктор: восстановление из WAL и открытие блоков могут упасть.
    public static LocalTimeSeriesDB open(DbOptions options) {
        Objects.requireNonNull(options, "options");
        throw new UnsupportedOperationException("lab2");
    }

    public DbOptions options() {
        return options;
    }

    public DbPaths paths() {
        return paths;
    }

    @Override
    public void append(Labels labels, long timestamp, double value) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public void appendBatch(List<SampleInput> batch) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public QueryResult query(Query query) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Set<String> labelNames() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Set<String> labelValues(String name) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public void compact() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public void deleteBefore(long timestamp) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public DbStats stats() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("lab2");
    }
}
