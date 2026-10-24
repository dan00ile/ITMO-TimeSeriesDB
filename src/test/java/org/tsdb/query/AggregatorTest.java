package org.tsdb.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.tsdb.model.Aggregation;

class AggregatorTest {

    private static double run(Aggregation agg, double... values) {
        Aggregator a = Aggregator.of(agg);
        for (double v : values) {
            a.add(v);
        }
        return a.result();
    }

    @Test
    void basicFunctions() {
        assertEquals(6.0, run(Aggregation.SUM, 1, 2, 3));
        assertEquals(2.0, run(Aggregation.AVG, 1, 2, 3));
        assertEquals(-1.0, run(Aggregation.MIN, 3, -1, 2));
        assertEquals(3.0, run(Aggregation.MAX, 3, -1, 2));
        assertEquals(3.0, run(Aggregation.COUNT, 3, -1, 2));
    }

    @Test
    void emptyResults() {
        assertEquals(0.0, run(Aggregation.SUM));
        assertEquals(0.0, run(Aggregation.COUNT));
        assertEquals(Double.NaN, run(Aggregation.AVG));
        assertEquals(Double.NaN, run(Aggregation.MIN));
        assertEquals(Double.NaN, run(Aggregation.MAX));
    }

    @Test
    void resetStartsNewWindow() {
        Aggregator a = Aggregator.of(Aggregation.AVG);
        a.add(10);
        a.reset();
        a.add(2);
        a.add(4);
        assertEquals(3.0, a.result());
    }

    @Test
    void noneRejected() {
        assertThrows(IllegalArgumentException.class, () -> Aggregator.of(Aggregation.NONE));
    }
}
