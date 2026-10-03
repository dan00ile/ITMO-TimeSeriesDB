package org.tsdb.index;

// Отсортированный по возрастанию набор seriesId без повторов.
public interface Postings {
    PostingsIterator iterator();

    boolean isEmpty();

    /** @throws IllegalArgumentException если ids не строго возрастают */
    static Postings of(long... sortedIds) {
        throw new UnsupportedOperationException("lab2");
    }

    /** a ∩ b. */
    static Postings intersect(Postings a, Postings b) {
        throw new UnsupportedOperationException("lab2");
    }

    /** a ∪ b. */
    static Postings union(Postings a, Postings b) {
        throw new UnsupportedOperationException("lab2");
    }

    /** a \ b. */
    static Postings without(Postings a, Postings b) {
        throw new UnsupportedOperationException("lab2");
    }
}
