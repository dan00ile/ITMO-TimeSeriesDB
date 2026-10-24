package org.tsdb.encoding;

import java.util.Objects;

public final class GorillaDecoder implements SampleIterator {

    private final ByteBitReader reader;
    private final int numSamples;

    private int currentIndex = -1;

    private long previousTimestamp;
    private long previousDelta;
    private long previousValueBits;

    private int previousLeading = -1;
    private int previousTrailing = -1;

    private long currentTimestamp;
    private double currentValue;

    public GorillaDecoder(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        if (bytes.length < 2) {
            throw new IllegalArgumentException(
                    "Chunk must contain a 2-byte header"
            );
        }

        numSamples = ((bytes[0] & 0xFF) << 8)
                | (bytes[1] & 0xFF);

        byte[] encoded = new byte[bytes.length - 2];

        System.arraycopy(
                bytes, 2,
                encoded, 0,
                encoded.length
        );

        reader = new ByteBitReader(encoded);
    }

    @Override
    public boolean next() {
        if (currentIndex + 1 >= numSamples) {
            return false;
        }

        if (currentIndex == -1) {
            previousTimestamp = reader.readBits(64);
            previousValueBits = reader.readBits(64);

        } else if (currentIndex == 0) {
            long delta = readZigZagVarint();

            previousTimestamp = Math.addExact(
                    previousTimestamp, delta
            );
            previousDelta = delta;
            previousValueBits = readXorValue();

        } else {
            long dod = readDeltaOfDelta();

            previousDelta = Math.addExact(previousDelta, dod);
            previousTimestamp = Math.addExact(
                    previousTimestamp, previousDelta
            );
            previousValueBits = readXorValue();
        }

        currentTimestamp = previousTimestamp;
        currentValue = Double.longBitsToDouble(previousValueBits);

        currentIndex++;
        return true;
    }

    private long readZigZagVarint() {
        long encoded = 0;

        for (int i = 0; i < 10; i++) {
            int b = reader.readByte();

            // В последнем байте 64-битного varint
            // допустим только один бит данных.
            if (i == 9 && (b & 0xFE) != 0) {
                throw new IllegalArgumentException(
                        "Invalid 64-bit varint"
                );
            }

            encoded |= (long) (b & 0x7F) << (7 * i);

            if ((b & 0x80) == 0) {
                return (encoded >>> 1) ^ -(encoded & 1L);
            }
        }

        throw new IllegalArgumentException(
                "Varint is too long"
        );
    }

    private long readDeltaOfDelta() {
        if (!reader.readBit()) {
            return 0;
        }

        int width;

        if (!reader.readBit()) {
            width = 14;
        } else if (!reader.readBit()) {
            width = 17;
        } else if (!reader.readBit()) {
            width = 20;
        } else {
            width = 64;
        }

        long value = reader.readBits(width);

        // Восстанавливаем знак числа.
        if (width == 64) {
            return value;
        }

        return (value << (64 - width)) >> (64 - width);
    }

    private long readXorValue() {
        // Первый бит 0 означает, что значение не изменилось.
        if (!reader.readBit()) {
            return previousValueBits;
        }

        int leading;
        int trailing;
        int significantBits;

        if (!reader.readBit()) {
            // Используется предыдущее окно.
            if (previousLeading < 0) {
                throw new IllegalArgumentException(
                        "Previous XOR window is not defined"
                );
            }

            leading = previousLeading;
            trailing = previousTrailing;
            significantBits = 64 - leading - trailing;

        } else {
            // Читаем параметры нового окна.
            leading = (int) reader.readBits(5);
            significantBits = (int) reader.readBits(6);

            if (significantBits == 0) {
                significantBits = 64;
            }

            if (leading + significantBits > 64) {
                throw new IllegalArgumentException(
                        "Invalid XOR window"
                );
            }

            trailing = 64 - leading - significantBits;

            previousLeading = leading;
            previousTrailing = trailing;
        }

        long meaningfulBits = reader.readBits(significantBits);
        long xor = meaningfulBits << trailing;

        return previousValueBits ^ xor;
    }

    @Override
    public long timestamp() {
        if (currentIndex < 0) {
            throw new IllegalStateException(
                    "Call next() before timestamp()"
            );
        }

        return currentTimestamp;
    }

    @Override
    public double value() {
        if (currentIndex < 0) {
            throw new IllegalStateException(
                    "Call next() before value()"
            );
        }

        return currentValue;
    }
}