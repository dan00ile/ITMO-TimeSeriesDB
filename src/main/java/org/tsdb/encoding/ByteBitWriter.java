package org.tsdb.encoding;

import java.io.ByteArrayOutputStream;

public final class ByteBitWriter implements BitWriter {

    private final ByteArrayOutputStream buffer =
            new ByteArrayOutputStream();

    private int currentByte = 0;
    private int bitsInCurrentByte = 0;
    private long bitLength = 0;

    @Override
    public void writeBit(boolean bit) {
        currentByte <<= 1;

        if (bit) {
            currentByte |= 1;
        }

        bitsInCurrentByte++;
        bitLength++;

        if (bitsInCurrentByte == 8) {
            buffer.write(currentByte);
            currentByte = 0;
            bitsInCurrentByte = 0;
        }
    }

    @Override
    public void writeBits(long value, int count) {
        if (count < 0 || count > 64) {
            throw new IllegalArgumentException(
                    "count must be between 0 and 64"
            );
        }

        for (int i = count - 1; i >= 0; i--) {
            writeBit(((value >>> i) & 1L) != 0);
        }
    }

    @Override
    public void writeByte(int b) {
        writeBits(b & 0xFF, 8);
    }

    @Override
    public byte[] toByteArray() {
        byte[] completed = buffer.toByteArray();

        if (bitsInCurrentByte == 0) {
            return completed;
        }

        byte[] result = new byte[completed.length + 1];

        System.arraycopy(
                completed, 0,
                result, 0,
                completed.length
        );

        result[completed.length] =
                (byte) (currentByte << (8 - bitsInCurrentByte));

        return result;
    }

    @Override
    public long bitLength() {
        return bitLength;
    }
}