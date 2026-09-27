package org.tsdb.block;

import java.nio.file.Path;

/**
 * Открывает существующие блоки с диска.
 */
public interface BlockReader {

    /**
     * Открыть блок.
     *
     * @throws CorruptedBlockException
     *         если meta/index/chunks повреждены
     */
    Block open(Path dir);
}