package org.tsdb.encoding;

/**
 * Интерфейс чтения chunk.
 *
 * Закрытый chunk является неизменяемым.
 */
public interface Chunk {

    ChunkEncoding encoding();

    int numSamples();

    long minTime();

    long maxTime();

    /**
     * Сериализованное представление chunk.
     */
    byte[] bytes();

    /**
     * Последовательное чтение точек chunk.
     */
    SampleIterator iterator();
}