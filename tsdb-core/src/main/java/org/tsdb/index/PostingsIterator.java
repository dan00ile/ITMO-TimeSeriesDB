package org.tsdb.index;

// Курсор по postings.
public interface PostingsIterator {
    boolean next();

    long at();
}
