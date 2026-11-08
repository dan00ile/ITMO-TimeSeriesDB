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
     * Id серий назначаются заново: 1..n в порядке Labels.canonical().
     *
     * @throws IllegalArgumentException если снимок пуст (нет серий)
     */
    BlockMeta write(
            HeadSnapshot snapshot,
            Path targetDir
    );

    /**
     * Слить несколько блоков в один.
     * Id серий назначаются заново: 1..n в порядке Labels.canonical().
     */
    BlockMeta merge(
            List<Block> blocks,
            Path targetDir
    );
}