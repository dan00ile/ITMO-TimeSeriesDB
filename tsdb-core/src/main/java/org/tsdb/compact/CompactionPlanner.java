package org.tsdb.compact;

import org.tsdb.block.BlockMeta;

import java.util.List;

/**
 * Определяет, какие блоки нужно объединить.
 */
public interface CompactionPlanner {

    /**
     * Возвращает группы блоков,
     * которые стоит слить.
     *
     * Пустой список означает,
     * что сейчас ничего делать не нужно.
     */
    List<List<BlockMeta>> plan(
            List<BlockMeta> blocks
    );
}