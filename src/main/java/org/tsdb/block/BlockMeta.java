package org.tsdb.block;

import java.util.List;
import java.util.Objects;

/**
 * Метаданные неизменяемого блока на диске.
 */
public record BlockMeta(
        String ulid,
        long minTime,
        long maxTime,
        long numSeries,
        long numSamples,
        long numChunks,
        int compactionLevel,
        List<String> sources
) {

    public BlockMeta {
        Objects.requireNonNull(ulid, "ulid");

        sources = List.copyOf(
                Objects.requireNonNull(sources, "sources")
        );
    }
}