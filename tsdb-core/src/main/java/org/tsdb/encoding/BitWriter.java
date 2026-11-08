package org.tsdb.encoding;

/**
 * Пишет биты в растущий буфер.
 * Используется Gorilla-кодером.
 */
public interface BitWriter {

    void writeBit(boolean bit);

    /**
     * Записывает count младших бит value,
     * старшим битом вперёд.
     */
    void writeBits(long value, int count);

    void writeByte(int b);

    byte[] toByteArray();

    long bitLength();
}