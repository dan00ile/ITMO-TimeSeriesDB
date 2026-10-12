package org.tsdb.encoding;

import java.util.Objects;

public final class ByteBitReader implements BitReader {

    private final byte[] data;
    private final long bitLimit;
    private long bitPosition = 0;

    public ByteBitReader(byte[] data) {
        this(data, (long) data.length * 8);
    }

    public ByteBitReader(byte[] data, long bitLimit) {
        Objects.requireNonNull(data, "data");

        if (bitLimit < 0 || bitLimit > (long) data.length * 8) {
            throw new IllegalArgumentException("Invalid bit limit");
        }

        this.data = data.clone();
        this.bitLimit = bitLimit;
    }

    @Override
    public boolean readBit() {
        if (remaining() == 0) {
            throw new IllegalStateException("No bits remaining");
        }

        int byteIndex = (int) (bitPosition / 8);
        int bitIndex = 7 - (int) (bitPosition % 8);

        int currentByte = data[byteIndex] & 0xFF;
        boolean result = ((currentByte >>> bitIndex) & 1) != 0;

        bitPosition++;
        return result;
    }

    @Override
    public long readBits(int count) {
        if (count < 0 || count > 64) {
            throw new IllegalArgumentException(
                    "count must be between 0 and 64"
            );
        }

        if (remaining() < count) {
            throw new IllegalStateException("Not enough bits");
        }

        long result = 0;

        for (int i = 0; i < count; i++) {
            result = (result << 1) | (readBit() ? 1L : 0L);
        }

        return result;
    }

    @Override
    public int readByte() {
        return (int) readBits(8);
    }

    @Override
    public long remaining() {
        return bitLimit - bitPosition;
    }
}