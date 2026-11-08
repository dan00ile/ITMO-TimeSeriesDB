package org.tsdb.encoding;

import java.util.Objects;

public final class SealedGorillaChunk implements Chunk {

    private final byte[] data;
    private final int numSamples;
    private final long minTime;
    private final long maxTime;

    public SealedGorillaChunk(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        if (bytes.length < 2) {
            throw new IllegalArgumentException(
                    "Chunk must contain a 2-byte header"
            );
        }

        this.data = bytes.clone();

        this.numSamples = ((data[0] & 0xFF) << 8)
                | (data[1] & 0xFF);

        // Восстанавливаем временные границы из точек.
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        SampleIterator iterator = new GorillaDecoder(data);

        while (iterator.next()) {
            long timestamp = iterator.timestamp();

            min = Math.min(min, timestamp);
            max = Math.max(max, timestamp);
        }

        this.minTime = min;
        this.maxTime = max;
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
    public byte[] bytes() {
        return data.clone();
    }

    @Override
    public SampleIterator iterator() {
        return new GorillaDecoder(data);
    }
}