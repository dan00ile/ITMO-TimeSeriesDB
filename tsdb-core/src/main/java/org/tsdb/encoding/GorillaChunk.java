package org.tsdb.encoding;

import org.tsdb.model.OutOfOrderSampleException;

public final class GorillaChunk implements AppendableChunk {

    private final int maxSamples;

    private final ByteBitWriter writer = new ByteBitWriter();

    private int numSamples = 0;

    private long minTime = Long.MAX_VALUE;
    private long maxTime = Long.MIN_VALUE;

    private long previousTimestamp;
    private long previousDelta;
    private long previousValueBits;

    private int previousLeading = -1;
    private int previousTrailing = -1;

    private boolean sealed = false;

    private byte[] sealedBytes;

    public GorillaChunk() {
        this(120);
    }

    public GorillaChunk(int maxSamples) {
        if (maxSamples < 1 || maxSamples > 65535) {
            throw new IllegalArgumentException(
                    "maxSamples must be between 1 and 65535"
            );
        }

        this.maxSamples = maxSamples;
    }

    @Override
    public ChunkEncoding encoding() {
        return ChunkEncoding.GORILLA_XOR;
    }

    @Override
    public int numSamples() {
        return numSamples;
    }

    @Override
    public long minTime() {
        return minTime;
    }

    @Override
    public long maxTime() {
        return maxTime;
    }

    @Override
    public boolean isFull() {
        return numSamples >= maxSamples;
    }

    @Override
    public void append(long timestamp, double value) {
        if (sealed) {
            throw new IllegalStateException("Chunk is sealed");
        }

        if (isFull()) {
            throw new IllegalStateException("Chunk is full");
        }

        if (numSamples > 0 && timestamp <= previousTimestamp) {
            throw new OutOfOrderSampleException(
                    0L,
                    timestamp,
                    previousTimestamp
            );
        }

        if (numSamples == 0) {
            writer.writeBits(timestamp, 64);
            writer.writeBits(Double.doubleToRawLongBits(value), 64);

            minTime = timestamp;
        } else if (numSamples == 1) {
            long delta = Math.subtractExact(timestamp, previousTimestamp);

            writeZigZagVarint(delta);
            writeXorValue(Double.doubleToRawLongBits(value));

            previousDelta = delta;
        } else {
            long delta = Math.subtractExact(timestamp, previousTimestamp); // исключение при переполнении
            long dod = Math.subtractExact(delta, previousDelta);

            writeDeltaOfDelta(dod);
            writeXorValue(Double.doubleToRawLongBits(value));

            previousDelta = delta;
        }

        previousTimestamp = timestamp;
        previousValueBits = Double.doubleToRawLongBits(value);
        maxTime = timestamp;
        numSamples++;
    }

    private void writeZigZagVarint(long value) {
        long encoded = (value << 1) ^ (value >> 63);
        writeUnsignedVarint(encoded);
    }

    private void writeUnsignedVarint(long value) {
        while ((value & ~0x7FL) != 0) {
            writer.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }

        writer.writeByte((int) value);
    }

    private void writeXorValue(long valueBits) {
        long xor = previousValueBits ^ valueBits;

        if (xor == 0) {
            writer.writeBit(false);
            return;
        }

        writer.writeBit(true);

        int leading = Math.min(
                Long.numberOfLeadingZeros(xor), 31
        );

        int trailing = Long.numberOfTrailingZeros(xor);

        if (previousLeading >= 0
                && leading >= previousLeading
                && trailing >= previousTrailing) {

            writer.writeBit(false);

            int significantBits =
                    64 - previousLeading - previousTrailing;

            writer.writeBits(
                    xor >>> previousTrailing,
                    significantBits
            );

        } else {
            writer.writeBit(true);

            int significantBits = 64 - leading - trailing;

            writer.writeBits(leading, 5);
            writer.writeBits(significantBits & 0x3F, 6);
            writer.writeBits(xor >>> trailing, significantBits);

            previousLeading = leading;
            previousTrailing = trailing;
        }
    }

    private void writeDeltaOfDelta(long dod) {
        if (dod == 0) {
            writer.writeBit(false);
        } else if (dod >= -8192 && dod <= 8191) {
            writer.writeBits(0b10, 2);
            writer.writeBits(dod, 14);
        } else if (dod >= -65536 && dod <= 65535) {
            writer.writeBits(0b110, 3);
            writer.writeBits(dod, 17);
        } else if (dod >= -524288 && dod <= 524287) {
            writer.writeBits(0b1110, 4);
            writer.writeBits(dod, 20);
        } else {
            writer.writeBits(0b1111, 4);
            writer.writeBits(dod, 64);
        }
    }

    @Override
    public byte[] bytes() {
        if (sealed) {
            return sealedBytes.clone();
        }

        byte[] encoded = writer.toByteArray();

        byte[] result = new byte[2 + encoded.length];

        result[0] = (byte) ((numSamples >>> 8) & 0xFF);
        result[1] = (byte) (numSamples & 0xFF);

        System.arraycopy(
                encoded, 0,
                result, 2,
                encoded.length
        );

        return result;
    }

    @Override
    public SampleIterator iterator() {
        return new GorillaDecoder(bytes());
    }

    @Override
    public Chunk seal() {
        if (!sealed) {
            sealedBytes = bytes();
            sealed = true;
        }

        return new SealedGorillaChunk(sealedBytes);
    }
}