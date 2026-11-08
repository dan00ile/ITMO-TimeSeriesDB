package org.tsdb.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.tsdb.index.InvertedIndex;
import org.tsdb.index.Postings;
import org.tsdb.index.PostingsIterator;
import org.tsdb.index.SeriesRegistry;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.Series;

// Registry и индекс в одном объекте, select — перебором. Id выдаются подряд с 1.
// В поиске участвуют только серии, добавленные через add: так видно, если фасад забыл про индекс.
public final class FakeIndex implements SeriesRegistry, InvertedIndex {

    private final Map<Labels, Long> ids = new HashMap<>();
    private final TreeMap<Long, Labels> registered = new TreeMap<>();
    private final TreeMap<Long, Labels> indexed = new TreeMap<>();

    @Override
    public synchronized long getOrCreate(Labels labels) {
        Long id = ids.get(labels);
        if (id == null) {
            id = ids.size() + 1L;
            ids.put(labels, id);
            registered.put(id, labels);
        }
        return id;
    }

    @Override
    public synchronized OptionalLong get(Labels labels) {
        Long id = ids.get(labels);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    @Override
    public synchronized Optional<Labels> labels(long id) {
        return Optional.ofNullable(registered.get(id));
    }

    @Override
    public synchronized int size() {
        return registered.size();
    }

    @Override
    public synchronized Stream<Series> all() {
        List<Series> all = new ArrayList<>();
        for (Map.Entry<Long, Labels> e : registered.entrySet()) {
            all.add(new Series(e.getKey(), e.getValue()));
        }
        return all.stream();
    }

    @Override
    public synchronized void add(long id, Labels labels) {
        if (!indexed.isEmpty() && id <= indexed.lastKey()) {
            throw new IllegalArgumentException("id " + id + " <= " + indexed.lastKey());
        }
        indexed.put(id, labels);
    }

    @Override
    public synchronized Postings postings(String name, String value) {
        return select(List.of(new Matcher.Eq(name, value)));
    }

    @Override
    public synchronized Set<String> labelNames() {
        Set<String> names = new TreeSet<>();
        for (Labels labels : indexed.values()) {
            for (Label label : labels.labels()) {
                names.add(label.name());
            }
        }
        return names;
    }

    @Override
    public synchronized Set<String> labelValues(String name) {
        Set<String> values = new TreeSet<>();
        for (Labels labels : indexed.values()) {
            labels.get(name).ifPresent(values::add);
        }
        return values;
    }

    @Override
    public synchronized Postings select(List<Matcher> matchers) {
        List<Long> found = new ArrayList<>();
        for (Map.Entry<Long, Labels> e : indexed.entrySet()) {
            boolean ok = true;
            for (Matcher m : matchers) {
                ok = ok && matches(m, e.getValue());
            }
            if (ok) {
                found.add(e.getKey());
            }
        }
        return new ListPostings(found);
    }

    private static boolean matches(Matcher m, Labels labels) {
        String value = labels.get(m.name()).orElse("");
        return switch (m) {
            case Matcher.Eq eq -> value.equals(eq.value());
            case Matcher.NotEq ne -> !value.equals(ne.value());
            case Matcher.Regex re -> value.matches(re.pattern());
            case Matcher.NotRegex nr -> !value.matches(nr.pattern());
        };
    }

    private record ListPostings(List<Long> ids) implements Postings {
        @Override
        public PostingsIterator iterator() {
            return new PostingsIterator() {
                private int i = -1;

                @Override
                public boolean next() {
                    i++;
                    return i < ids.size();
                }

                @Override
                public long at() {
                    return ids.get(i);
                }
            };
        }

        @Override
        public boolean isEmpty() {
            return ids.isEmpty();
        }
    }
}
