package org.tsdb.compact;

import org.junit.jupiter.api.Test;
import org.tsdb.block.BlockMeta;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeveledCompactionPlannerTest {

    private BlockMeta block(String id, int level, long time) {
        return new BlockMeta(
                id, time, time + 100,
                1, 10, 1, level, List.of()
        );
    }

    @Test
    void selectsThreeBlocksOfSameLevel() {
        CompactionPlanner planner = new LeveledCompactionPlanner(3);

        BlockMeta a = block("a", 0, 100);
        BlockMeta b = block("b", 0, 200);
        BlockMeta c = block("c", 0, 300);

        assertEquals(
                List.of(List.of(a, b, c)),
                planner.plan(List.of(c, a, b))
        );
    }

    @Test
    void doesNotMixLevels() {
        CompactionPlanner planner = new LeveledCompactionPlanner(3);

        assertTrue(planner.plan(List.of(
                block("a", 0, 100),
                block("b", 0, 200),
                block("c", 1, 300)
        )).isEmpty());
    }

    @Test
    void rejectsInvalidMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeveledCompactionPlanner(1));
    }
}