package org.tsdb.compact;

import org.tsdb.block.BlockMeta;

import java.util.List;
import java.util.Objects;

public final class TimeRetentionPolicy implements RetentionPolicy {

    private final long retentionMs;

    public TimeRetentionPolicy(long retentionMs) {
        if (retentionMs < 0) {
            throw new IllegalArgumentException(
                    "retentionMs must be >= 0"
            );
        }
        this.retentionMs = retentionMs;
    }

    @Override
    public List<BlockMeta> expired(List<BlockMeta> blocks, long now) {
        Objects.requireNonNull(blocks, "blocks");

        if (retentionMs == 0) {
            return List.of();
        }

        long cutoff;
        try {
            cutoff = Math.subtractExact(now, retentionMs);
        } catch (ArithmeticException e) {
            return List.of();
        }

        return blocks.stream()
                .filter(block -> block.maxTime() < cutoff)
                .toList();
    }
}