package org.tsdb.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher.Eq;
import org.tsdb.model.Matcher.NotEq;
import org.tsdb.model.Matcher.NotRegex;
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
    void selectIntersectsPostings() {
        Postings p = sampleIndex().select(List.of(new Eq("__name__", "cpu_usage"), new Eq("host", "web-1")));
        assertArrayEquals(new long[] {1, 2}, toArray(p));
    }

    @Test
    void selectWithRegexAndNotEq() {
        Postings p = sampleIndex().select(List.of(
                new Eq("__name__", "cpu_usage"), new Regex("host", "web-.*"), new NotEq("core", "1")));
        assertArrayEquals(new long[] {1, 3}, toArray(p));
    }

    @Test
    void regexMatchesWholeValue() {
        Postings p = sampleIndex().select(List.of(new Eq("__name__", "cpu_usage"), new Regex("host", "web")));
        assertArrayEquals(new long[] {}, toArray(p));
    }

    @Test
    void notRegexExcludes() {
        Postings p = sampleIndex().select(List.of(new Eq("__name__", "cpu_usage"), new NotRegex("host", "web-.*")));
        assertArrayEquals(new long[] {4}, toArray(p));
    }

    @Test
    void missingLabelEqualsEmptyString() {
        InvertedIndex idx = sampleIndex();
        // Eq(core, "") — серии без лейбла core
        assertArrayEquals(new long[] {5}, toArray(idx.select(List.of(new Eq("core", "")))));
        // NotEq(core, "1") пропускает серию без core
        assertArrayEquals(new long[] {1, 5},
                toArray(idx.select(List.of(new Eq("host", "web-1"), new NotEq("core", "1")))));
        // NotEq(core, "") — только серии, у которых core есть
        assertArrayEquals(new long[] {1, 2},
                toArray(idx.select(List.of(new Eq("host", "web-1"), new NotEq("core", "")))));
        // Regex, совпадающий с "", захватывает и серии без лейбла
        assertArrayEquals(new long[] {2, 5},
                toArray(idx.select(List.of(new Eq("host", "web-1"), new Regex("core", "1|")))));
    }

    @Test
    void unknownLabelGivesEmpty() {
        InvertedIndex idx = sampleIndex();
        assertArrayEquals(new long[] {}, toArray(idx.postings("dc", "eu")));
        assertArrayEquals(new long[] {}, toArray(idx.select(List.of(new Eq("host", "nope")))));
        assertEquals(Set.of(), idx.labelValues("dc"));
    }

    @Test
    void labelNamesAndValues() {
        InvertedIndex idx = sampleIndex();
        assertEquals(Set.of("__name__", "host", "core"), idx.labelNames());
        assertEquals(Set.of("web-1", "web-2", "db-1"), idx.labelValues("host"));
    }

    @Test
    void selectWithoutEqRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleIndex().select(List.of(new NotEq("host", "web-1"))));
    }

    @Test
    void addRequiresIncreasingIds() {
        InvertedIndex idx = sampleIndex();
        assertThrows(IllegalArgumentException.class, () -> idx.add(5, Labels.of("x")));
        assertThrows(IllegalArgumentException.class, () -> idx.add(3, Labels.of("x")));
    }

    @Test
    void postingsSnapshotUnaffectedByLaterAdds() {
        InvertedIndex idx = sampleIndex();
        Postings before = idx.postings("host", "web-1");
        for (long id = 6; id < 100; id++) {
            idx.add(id, Labels.of("cpu_usage", "host", "web-1"));
        }
        assertArrayEquals(new long[] {1, 2, 5}, toArray(before));
    }
}
