package org.tsdb.encoding;

/**
 * Кодирует и декодирует chunk.
 */
public interface ChunkCodec {

    /**
     * Создать новый chunk,
     * в который можно записывать точки.
     */
    AppendableChunk newAppendable();

    /**
     * Восстановить chunk из сериализованных байтов.
     */
    Chunk decode(byte[] bytes);
}