package org.tsdb.compact;

import org.tsdb.block.BlockMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LeveledCompactionPlanner implements CompactionPlanner {

    private final int minBlocks;

    public LeveledCompactionPlanner() {
        this(3);
    }

    public LeveledCompactionPlanner(int minBlocks) {
        if (minBlocks < 2) {
            throw new IllegalArgumentException(
                    "minBlocks must be >= 2");
        }
        this.minBlocks = minBlocks;
    }

    @Override
    public List<List<BlockMeta>> plan(List<BlockMeta> blocks) {
        Objects.requireNonNull(blocks, "blocks");

        var groups = new java.util.TreeMap<Integer, List<BlockMeta>>();

        for (BlockMeta block : blocks) {
            groups.computeIfAbsent(
                    block.compactionLevel(),
                    ignored -> new ArrayList<>()
            ).add(block);
        }

        List<List<BlockMeta>> result = new ArrayList<>();

        for (List<BlockMeta> sameLevel : groups.values()) {
            sameLevel.sort(
                    Comparator.comparingLong(BlockMeta::minTime)
                            .thenComparing(BlockMeta::ulid)
            );

            for (int i = 0; i + minBlocks <= sameLevel.size();
                 i += minBlocks) {
                result.add(List.copyOf(
                        sameLevel.subList(i, i + minBlocks)));
            }
        }

        return result;
    }
}