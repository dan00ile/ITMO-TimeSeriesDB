package org.tsdb.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class QueryTest {
    private static final List<Matcher> CPU = List.of(new Matcher.Eq("__name__", "cpu_usage"));

    @Test
    void rangeThenAggregate() {
        Query q = Query.range(CPU, 0, 1_000).aggregate(Aggregation.AVG, List.of("host"), 100);
        assertEquals(Aggregation.AVG, q.aggregation());
        assertEquals(List.of("host"), q.groupBy());
        assertEquals(100, q.step());
    }

    @Test
    void endBeforeStartRejected() {
        assertThrows(IllegalArgumentException.class, () -> Query.range(CPU, 10, 5));
    }

    @Test
    void onlyNegativeMatchersRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Query.range(List.of(new Matcher.NotEq("host", "web-1")), 0, 1));
    }

    @Test
    void groupByWithoutAggregationRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Query(CPU, 0, 1, Aggregation.NONE, List.of("host"), 0));
    }

    @Test
    void invalidRegexRejectedEagerly() {
        assertThrows(IllegalArgumentException.class, () -> new Matcher.Regex("host", "web-("));
    }
}
