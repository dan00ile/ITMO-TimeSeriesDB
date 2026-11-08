package org.tsdb.encoding;

/**
 * Формат кодирования chunk.
 */
public enum ChunkEncoding {

    GORILLA_XOR((byte) 1);

    private final byte id;

    ChunkEncoding(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }
}