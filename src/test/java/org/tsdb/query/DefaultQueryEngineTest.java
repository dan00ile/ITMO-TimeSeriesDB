package org.tsdb.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.tsdb.testing.Fixtures.group;
import static org.tsdb.testing.Fixtures.samplesOf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.tsdb.head.MemHead;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.testing.ListChunkCodec;

// Источники — MemHead на заглушечном кодеке: движок видит их только как Queryable.
class DefaultQueryEngineTest {
    private static final QueryEngine ENGINE = new DefaultQueryEngine();
    private static final List<Matcher> CPU = List.of(new Matcher.Eq("__name__", "cpu_usage"));

    private static MemHead source() {
        return new MemHead(new ListChunkCodec(2));
    }

    private static void put(MemHead head, Labels labels, long ts, double value) {
        long id = head.registry().get(labels).orElseGet(() -> {
            long newId = head.registry().getOrCreate(labels);
            head.index().add(newId, labels);
            return newId;
        });
        head.append(id, ts, value);
    }

    @Test
    void rawSeriesStitchedAcrossSourcesByLabels() {
        Labels web1 = Labels.of("cpu_usage", "host", "web-1");
        Labels web2 = Labels.of("cpu_usage", "host", "web-2");
        MemHead older = source();
        MemHead newer = source();
        put(older, web2, 10, 2.0); // id 1 в older — web-2
        put(older, web1, 10, 1.0);
        put(older, web1, 20, 1.1);
        put(newer, web1, 30, 1.2); // id 1 в newer — web-1
        put(newer, web1, 40, 1.3);
        put(newer, Labels.of("mem_usage", "host", "web-1"), 30, 9.0);

        QueryResult res = ENGINE.execute(Query.range(CPU, 15, 35), List.of(older, newer));

        assertEquals(List.of(web1), res.series().stream().map(s -> s.labels()).toList(),
                "web-2 без точек в диапазоне выброшена, mem_usage отсеян индексом");
        assertEquals(List.of(new Sample(20, 1.1), new Sample(30, 1.2)), samplesOf(res, web1));
    }

    @Test
    void resultSortedByCanonicalLabels() {
        MemHead head = source();
        put(head, Labels.of("cpu_usage", "host", "b"), 1, 1);
        put(head, Labels.of("cpu_usage", "host", "a"), 1, 1);
        QueryResult res = ENGINE.execute(Query.range(CPU, 0, 10), List.of(head));
        assertEquals(List.of("cpu_usage{host=\"a\"}", "cpu_usage{host=\"b\"}"),
                res.series().stream().map(s -> s.labels().canonical()).toList());
    }

    // Пример из lab2-implementation-java.md, раздел 7.
    @Test
    void avgByHostWithStep() {
        MemHead head = source();
        put(head, Labels.of("cpu_usage", "host", "a", "service", "api", "core", "0"), 110, 1.0);
        put(head, Labels.of("cpu_usage", "host", "a", "service", "api", "core", "0"), 160, 3.0);
        put(head, Labels.of("cpu_usage", "host", "a", "service", "api", "core", "1"), 120, 2.0);
        put(head, Labels.of("cpu_usage", "host", "a", "service", "api", "core", "1"), 170, 5.0);
        put(head, Labels.of("cpu_usage", "host", "b", "service", "api", "core", "0"), 130, 10.0);
        put(head, Labels.of("cpu_usage", "host", "b", "service", "api", "core", "0"), 180, 20.0);

        Query q = Query.range(List.of(new Matcher.Eq("__name__", "cpu_usage"), new Matcher.Eq("service", "api")),
                100, 200).aggregate(Aggregation.AVG, List.of("host"), 50);
        QueryResult res = ENGINE.execute(q, List.of(head));

        assertEquals(2, res.series().size());
        assertEquals(List.of(new Sample(100, 1.5), new Sample(150, 4.0)), samplesOf(res, group("host", "a")));
        assertEquals(List.of(new Sample(100, 10.0), new Sample(150, 20.0)), samplesOf(res, group("host", "b")));
    }

    @Test
    void withoutStepOnePointPerGroupAtEnd() {
        MemHead head = source();
        put(head, Labels.of("cpu_usage", "host", "a", "core", "0"), 10, 1.0);
        put(head, Labels.of("cpu_usage", "host", "a", "core", "1"), 20, 3.0);
        put(head, Labels.of("cpu_usage", "host", "b", "core", "0"), 10, 7.0);

        QueryResult sum = ENGINE.execute(Query.range(CPU, 0, 100).aggregate(Aggregation.SUM, List.of("host"), 0),
                List.of(head));
        assertEquals(List.of(new Sample(100, 4.0)), samplesOf(sum, group("host", "a")));
        assertEquals(List.of(new Sample(100, 7.0)), samplesOf(sum, group("host", "b")));

        QueryResult count = ENGINE.execute(Query.range(CPU, 0, 100).aggregate(Aggregation.COUNT, List.of(), 0),
                List.of(head));
        assertEquals(List.of(new Sample(100, 3.0)), samplesOf(count, group()));
    }

    @Test
    void sourcesOutsideRangeSkipped() {
        MemHead head = source();
        put(head, Labels.of("cpu_usage", "host", "a"), 10, 1.0);
        assertEquals(0, ENGINE.execute(Query.range(CPU, 100, 200), List.of(head)).series().size());
        assertEquals(0, ENGINE.execute(Query.range(CPU, 0, 100), List.of(source())).series().size());
    }
}
