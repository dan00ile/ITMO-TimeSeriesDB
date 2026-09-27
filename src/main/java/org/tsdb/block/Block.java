package org.tsdb.block;

import org.tsdb.encoding.Chunk;
import org.tsdb.index.InvertedIndex;
import org.tsdb.index.SeriesRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Неизменяемый блок данных на диске.
 */
public interface Block extends AutoCloseable {

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
    List<Chunk> chunks(
            long seriesId,
            long start,
            long end
    );

    @Override
    void close();
}