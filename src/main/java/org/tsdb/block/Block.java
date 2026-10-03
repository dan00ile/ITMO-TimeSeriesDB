package org.tsdb.block;

import org.tsdb.encoding.Chunk;
import org.tsdb.index.InvertedIndex;
import org.tsdb.index.Postings;
import org.tsdb.index.SeriesRegistry;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.query.Queryable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Неизменяемый блок данных на диске.
 */
public interface Block extends Queryable, AutoCloseable {

    BlockMeta meta();

    Path dir();

    /**
     * Registry этого блока.
     * Используется только для чтения.
     */
    SeriesRegistry registry();

    /**
     * Инвертированный индекс этого блока.
     * Используется только для чтения.
     */
    InvertedIndex index();

    /**
     * Возвращает chunk'и серии,
     * пересекающиеся с [start, end].
     */
    @Override
    List<Chunk> chunks(
            long seriesId,
            long start,
            long end
    );

    @Override
    default long minTime() {
        return meta().minTime();
    }

    @Override
    default long maxTime() {
        return meta().maxTime();
    }

    @Override
    default Postings select(List<Matcher> matchers) {
        return index().select(matchers);
    }

    @Override
    default Optional<Labels> labels(long id) {
        return registry().labels(id);
    }

    @Override
    void close();
}
