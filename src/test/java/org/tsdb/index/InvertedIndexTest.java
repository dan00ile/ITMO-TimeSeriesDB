package org.tsdb.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher.Eq;
import org.tsdb.model.Matcher.NotEq;
import org.tsdb.model.Matcher.Regex;

class InvertedIndexTest {

    static long[] toArray(Postings p) {
        java.util.stream.LongStream.Builder b = java.util.stream.LongStream.builder();
        PostingsIterator it = p.iterator();
        while (it.next()) {
            b.add(it.at());
        }
        return b.build().toArray();
    }

    static InvertedIndex sampleIndex() {
        InvertedIndex idx = new MemInvertedIndex();
        idx.add(1, Labels.of("cpu_usage", "host", "web-1", "core", "0"));
        idx.add(2, Labels.of("cpu_usage", "host", "web-1", "core", "1"));
        idx.add(3, Labels.of("cpu_usage", "host", "web-2", "core", "0"));
        idx.add(4, Labels.of("cpu_usage", "host", "db-1", "core", "0"));
        idx.add(5, Labels.of("mem_usage", "host", "web-1"));
        return idx;
    }

    @Test
    @Disabled("lab2")
    void selectIntersectsPostings() {
        Postings p = sampleIndex().select(List.of(new Eq("__name__", "cpu_usage"), new Eq("host", "web-1")));
        assertArrayEquals(new long[] {1, 2}, toArray(p));
    }

    @Test
    @Disabled("lab2")
    void selectWithRegexAndNotEq() {
        Postings p = sampleIndex().select(List.of(
                new Eq("__name__", "cpu_usage"), new Regex("host", "web-.*"), new NotEq("core", "1")));
        assertArrayEquals(new long[] {1, 3}, toArray(p));
    }
}
