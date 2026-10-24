package org.tsdb.compact;

import org.junit.jupiter.api.Test;
import org.tsdb.block.BlockMeta;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeRetentionPolicyTest {

    private BlockMeta block(String id, long minTime, long maxTime) {
        return new BlockMeta(
                id, minTime, maxTime,
                1, 10, 1, 0, List.of()
        );
    }

    @Test
    void expiresOnlyOldBlocks() {
        TimeRetentionPolicy policy = new TimeRetentionPolicy(1000);

        BlockMeta old = block("old", 100, 500);
        BlockMeta recent = block("recent", 1500, 1900);

        assertEquals(
                List.of(old),
                policy.expired(List.of(old, recent), 2000)
        );
    }

    @Test
    void zeroRetentionMeansForever() {
        TimeRetentionPolicy policy = new TimeRetentionPolicy(0);

        assertTrue(policy.expired(
                List.of(block("old", 1, 10)),
                100000
        ).isEmpty());
    }

    @Test
    void blockAtCutoffIsRetained() {
        TimeRetentionPolicy policy = new TimeRetentionPolicy(1000);

        assertTrue(policy.expired(
                List.of(block("boundary", 100, 1000)),
                2000
        ).isEmpty());
    }

    @Test
    void rejectsNegativeRetention() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeRetentionPolicy(-1));
    }
}