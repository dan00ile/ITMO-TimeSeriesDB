package org.tsdb.block;

import org.tsdb.head.HeadSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Записывает данные в блоки на диске.
 */
public interface BlockWriter {

    /**
     * Записать снимок Head в новый блок.
     */
    BlockMeta write(
            HeadSnapshot snapshot,
            Path targetDir
    );

    /**
     * Слить несколько блоков в один.
     */
    BlockMeta merge(
            List<Block> blocks,
            Path targetDir
    );
}