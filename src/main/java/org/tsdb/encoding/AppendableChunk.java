package org.tsdb.encoding;

/**
 * Chunk, в который ещё можно добавлять точки.
 */
public interface AppendableChunk extends Chunk {

    /**
     * Добавляет новую точку.
     *
     * @throws org.tsdb.model.OutOfOrderSampleException
     *         если timestamp <= времени последней точки
     */
    void append(long timestamp, double value);

    /**
     * Достигнут ли лимит размера chunk.
     */
    boolean isFull();

    /**
     * Закрывает chunk и возвращает
     * его неизменяемое представление.
     */
    Chunk seal();
}