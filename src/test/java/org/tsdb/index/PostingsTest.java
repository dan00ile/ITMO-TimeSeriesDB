package org.tsdb.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.tsdb.index.InvertedIndexTest.toArray;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PostingsTest {

    @Test
    @Disabled("lab2")
    void setOperations() {
        Postings a = Postings.of(1, 2, 3, 4);
        Postings b = Postings.of(1, 2, 5, 9);
        assertArrayEquals(new long[] {1, 2}, toArray(Postings.intersect(a, b)));
        assertArrayEquals(new long[] {1, 2, 3, 4, 5, 9}, toArray(Postings.union(a, b)));
        assertArrayEquals(new long[] {3, 4}, toArray(Postings.without(a, b)));
    }
}
