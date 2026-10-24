package org.tsdb.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.index.InvertedIndexTest.toArray;

import org.junit.jupiter.api.Test;

class PostingsTest {

    @Test
    void setOperations() {
        Postings a = Postings.of(1, 2, 3, 4);
        Postings b = Postings.of(1, 2, 5, 9);
        assertArrayEquals(new long[] {1, 2}, toArray(Postings.intersect(a, b)));
        assertArrayEquals(new long[] {1, 2, 3, 4, 5, 9}, toArray(Postings.union(a, b)));
        assertArrayEquals(new long[] {3, 4}, toArray(Postings.without(a, b)));
    }

    @Test
    void operationsWithEmpty() {
        Postings a = Postings.of(1, 5);
        Postings empty = Postings.of();
        assertTrue(empty.isEmpty());
        assertFalse(a.isEmpty());
        assertArrayEquals(new long[] {}, toArray(Postings.intersect(a, empty)));
        assertArrayEquals(new long[] {1, 5}, toArray(Postings.union(empty, a)));
        assertArrayEquals(new long[] {1, 5}, toArray(Postings.without(a, empty)));
        assertArrayEquals(new long[] {}, toArray(Postings.without(empty, a)));
    }

    @Test
    void disjointAndInterleaved() {
        Postings odd = Postings.of(1, 3, 5, 7);
        Postings even = Postings.of(2, 4, 6);
        assertArrayEquals(new long[] {}, toArray(Postings.intersect(odd, even)));
        assertArrayEquals(new long[] {1, 2, 3, 4, 5, 6, 7}, toArray(Postings.union(odd, even)));
        assertArrayEquals(new long[] {1, 3, 5, 7}, toArray(Postings.without(odd, even)));
    }

    @Test
    void ofRejectsUnsortedOrDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> Postings.of(3, 1));
        assertThrows(IllegalArgumentException.class, () -> Postings.of(1, 1));
    }
}
