package org.tsdb.index;

// Отсортированный по возрастанию набор seriesId без повторов.
// Операции над двумя наборами — один проход двумя указателями, O(|a| + |b|), результат неизменяемый.
public interface Postings {
    PostingsIterator iterator();

    boolean isEmpty();

    /** @throws IllegalArgumentException если ids не строго возрастают */
    static Postings of(long... sortedIds) {
        ArrayPostings.Builder b = new ArrayPostings.Builder();
        for (long id : sortedIds) {
            b.add(id);
        }
        return b.snapshot();
    }

    /** a ∩ b. */
    static Postings intersect(Postings a, Postings b) {
        PostingsIterator x = a.iterator();
        PostingsIterator y = b.iterator();
        ArrayPostings.Builder out = new ArrayPostings.Builder();
        boolean hx = x.next();
        boolean hy = y.next();
        while (hx && hy) {
            long u = x.at();
            long v = y.at();
            if (u < v) {
                hx = x.next();
            } else if (u > v) {
                hy = y.next();
            } else {
                out.add(u);
                hx = x.next();
                hy = y.next();
            }
        }
        return out.snapshot();
    }

    /** a ∪ b. */
    static Postings union(Postings a, Postings b) {
        PostingsIterator x = a.iterator();
        PostingsIterator y = b.iterator();
        ArrayPostings.Builder out = new ArrayPostings.Builder();
        boolean hx = x.next();
        boolean hy = y.next();
        while (hx || hy) {
            if (!hy || (hx && x.at() < y.at())) {
                out.add(x.at());
                hx = x.next();
            } else if (!hx || y.at() < x.at()) {
                out.add(y.at());
                hy = y.next();
            } else {
                out.add(x.at());
                hx = x.next();
                hy = y.next();
            }
        }
        return out.snapshot();
    }

    /** a \ b. */
    static Postings without(Postings a, Postings b) {
        PostingsIterator x = a.iterator();
        PostingsIterator y = b.iterator();
        ArrayPostings.Builder out = new ArrayPostings.Builder();
        boolean hx = x.next();
        boolean hy = y.next();
        while (hx) {
            if (!hy || x.at() < y.at()) {
                out.add(x.at());
                hx = x.next();
            } else if (x.at() > y.at()) {
                hy = y.next();
            } else {
                hx = x.next();
                hy = y.next();
            }
        }
        return out.snapshot();
    }
}
