package org.tsdb.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.tsdb.db.SampleInput;

class MetricsGeneratorTest {

    @Test
    void sameSeedGivesSameData() {
        MetricsGenerator a = new MetricsGenerator(3, 2, 1_000, 15_000, 7);
        MetricsGenerator b = new MetricsGenerator(3, 2, 1_000, 15_000, 7);
        for (int i = 0; i < 10; i++) {
            assertEquals(a.nextTick(), b.nextTick());
        }
    }

    @Test
    void onePointPerSeriesPerTick() {
        MetricsGenerator gen = new MetricsGenerator(3, 2, 1_000, 15_000, 7);
        assertEquals(6, gen.series().size());
        List<SampleInput> first = gen.nextTick();
        List<SampleInput> second = gen.nextTick();
        assertEquals(6, first.size());
        assertTrue(first.stream().allMatch(s -> s.timestamp() == 1_000));
        assertTrue(second.stream().allMatch(s -> s.timestamp() == 16_000));
        assertEquals(31_000, gen.nextTimestamp());
        assertTrue(second.stream().allMatch(s -> s.value() >= 0 && s.value() <= 1));
    }
}
