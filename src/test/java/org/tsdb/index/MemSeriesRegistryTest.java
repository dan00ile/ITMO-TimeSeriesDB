package org.tsdb.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.tsdb.model.Labels;
import org.tsdb.model.Series;

class MemSeriesRegistryTest {
    private static final Labels WEB1 = Labels.of("cpu_usage", "host", "web-1");
    private static final Labels WEB2 = Labels.of("cpu_usage", "host", "web-2");

    @Test
    void getOrCreateAssignsMonotonicIds() {
        MemSeriesRegistry r = new MemSeriesRegistry();
        assertEquals(1, r.getOrCreate(WEB1));
        assertEquals(2, r.getOrCreate(WEB2));
        assertEquals(1, r.getOrCreate(Labels.of("cpu_usage", "host", "web-1")));
        assertEquals(2, r.size());
        assertEquals(OptionalLong.of(2), r.get(WEB2));
        assertEquals(Optional.of(WEB1), r.labels(1));
        assertEquals(OptionalLong.empty(), r.get(Labels.of("x")));
        assertEquals(Optional.empty(), r.labels(42));
        assertEquals(List.of(new Series(1, WEB1), new Series(2, WEB2)), r.all().toList());
    }

    @Test
    void restoreSetsIdAndMovesCounter() {
        MemSeriesRegistry r = new MemSeriesRegistry();
        r.restore(7, WEB1);
        r.restore(7, WEB1); // идемпотентно
        assertEquals(7, r.getOrCreate(WEB1));
        assertEquals(8, r.getOrCreate(WEB2));
    }

    @Test
    void restoreConflictsRejected() {
        MemSeriesRegistry r = new MemSeriesRegistry();
        r.restore(7, WEB1);
        assertThrows(IllegalStateException.class, () -> r.restore(7, WEB2));
        assertThrows(IllegalStateException.class, () -> r.restore(8, WEB1));
    }

    @Test
    void concurrentGetOrCreateGivesOneIdPerSeries() throws Exception {
        MemSeriesRegistry r = new MemSeriesRegistry();
        ConcurrentHashMap<Integer, Long> seen = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = IntStream.range(0, 8).<Future<?>>mapToObj(t -> pool.submit(() -> {
                for (int i = 0; i < 1_000; i++) {
                    long id = r.getOrCreate(Labels.of("m", "i", Integer.toString(i)));
                    Long prev = seen.putIfAbsent(i, id);
                    if (prev != null && prev != id) {
                        throw new AssertionError("series " + i + " got ids " + prev + " and " + id);
                    }
                }
            })).toList();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(1_000, r.size());
    }
}
