package org.tsdb.encoding;

import java.util.Objects;

public final class GorillaCodec implements ChunkCodec {

    private final int maxSamples;

    public GorillaCodec() {
        this(120);
    }

    public GorillaCodec(int maxSamples) {
        if (maxSamples < 1 || maxSamples > 65535) {
            throw new IllegalArgumentException(
                    "maxSamples must be between 1 and 65535"
            );
        }

        this.maxSamples = maxSamples;
    }

    @Override
    public AppendableChunk newAppendable() {
        return new GorillaChunk(maxSamples);
    }

    @Override
    public Chunk decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        return new SealedGorillaChunk(bytes);
    }
}