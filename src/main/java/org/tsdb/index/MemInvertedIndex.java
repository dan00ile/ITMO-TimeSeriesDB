package org.tsdb.index;

import java.util.List;
import java.util.Set;

import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// In-memory инвертированный индекс для head
public final class MemInvertedIndex implements InvertedIndex {
    @Override
    public void add(long id, Labels labels) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Postings postings(String name, String value) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Set<String> labelNames() {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Set<String> labelValues(String name) {
        throw new UnsupportedOperationException("lab2");
    }

    @Override
    public Postings select(List<Matcher> matchers) {
        throw new UnsupportedOperationException("lab2");
    }
}
