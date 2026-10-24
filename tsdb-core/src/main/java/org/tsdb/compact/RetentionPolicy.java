package org.tsdb.compact;

import org.tsdb.block.BlockMeta;

import java.util.List;

/**
 * Политика хранения данных.
 */
public interface RetentionPolicy {

    /**
     * Возвращает блоки,
     * которые пора удалить.
     */
    List<BlockMeta> expired(
            List<BlockMeta> blocks,
            long now
    );
}