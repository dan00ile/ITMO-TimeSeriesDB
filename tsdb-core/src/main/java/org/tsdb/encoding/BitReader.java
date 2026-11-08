package org.tsdb.encoding;

/**
 * Читает биты из закодированного буфера.
 */
public interface BitReader {

    boolean readBit();

    long readBits(int count);

    int readByte();

    long remaining();
}