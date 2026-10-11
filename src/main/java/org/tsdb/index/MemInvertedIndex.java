package org.tsdb.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// In-memory инвертированный индекс для head.
// выданные Postings — неизменяемые снимки, их обход идёт уже без блокировки.
// Отсутствующий у серии лейбл считается равным "": Eq("core", "") — серии без core,
// NotEq("core", "1") пропускает серии без core.
public final class MemInvertedIndex implements InvertedIndex {
    private final Map<String, Map<String, ArrayPostings.Builder>> postings = new HashMap<>();
    private final ArrayPostings.Builder all = new ArrayPostings.Builder();

    @Override
    public synchronized void add(long id, Labels labels) {
        Objects.requireNonNull(labels, "labels");
        if (id <= all.last()) {
            throw new IllegalArgumentException("id " + id + " <= last added " + all.last());
        }
        all.add(id);
        for (Label l : labels.labels()) {
            postings.computeIfAbsent(l.name(), n -> new HashMap<>())
                    .computeIfAbsent(l.value(), v -> new ArrayPostings.Builder())
                    .add(id);
        }
    }

    @Override
    public synchronized Postings postings(String name, String value) {
        Map<String, ArrayPostings.Builder> values = postings.get(name);
        if (values == null) {
            return ArrayPostings.EMPTY;
        }
        ArrayPostings.Builder b = values.get(value);
        return b == null ? ArrayPostings.EMPTY : b.snapshot();
    }

    @Override
    public synchronized Set<String> labelNames() {
        return Set.copyOf(postings.keySet());
    }

    @Override
    public synchronized Set<String> labelValues(String name) {
        Map<String, ArrayPostings.Builder> values = postings.get(name);
        return values == null ? Set.of() : Set.copyOf(values.keySet());
    }

    // Пересечение позитивных matchers (Eq, Regex), затем вычитание негативных (NotEq, NotRegex).
    // Без единого Eq — IllegalArgumentException (как в Query): иначе пришлось бы начинать с «все серии».
    @Override
    public synchronized Postings select(List<Matcher> matchers) {
        if (matchers.stream().noneMatch(m -> m instanceof Matcher.Eq)) {
            throw new IllegalArgumentException("select needs at least one Eq matcher");
        }
        List<Postings> positive = new ArrayList<>();
        List<Postings> negative = new ArrayList<>();
        for (Matcher m : matchers) {
            switch (m) {
                case Matcher.Eq eq -> positive.add(equal(eq.name(), eq.value()));
                case Matcher.Regex re -> positive.add(regex(re.name(), re.pattern()));
                case Matcher.NotEq ne -> negative.add(equal(ne.name(), ne.value()));
                case Matcher.NotRegex nr -> negative.add(regex(nr.name(), nr.pattern()));
            }
        }
        Postings result = positive.getFirst();
        for (int i = 1; i < positive.size() && !result.isEmpty(); i++) {
            result = Postings.intersect(result, positive.get(i));
        }
        for (int i = 0; i < negative.size() && !result.isEmpty(); i++) {
            result = Postings.without(result, negative.get(i));
        }
        return result;
    }

    private Postings equal(String name, String value) {
        return value.isEmpty() ? withoutLabel(name) : postings(name, value);
    }

    // Полное совпадение, как в Prometheus: host=~"web" не совпадает с web-1.
    private Postings regex(String name, String pattern) {
        Pattern p = Pattern.compile("^(?:" + pattern + ")$");
        Postings result = p.matcher("").matches() ? withoutLabel(name) : ArrayPostings.EMPTY;
        Map<String, ArrayPostings.Builder> values = postings.getOrDefault(name, Map.of());
        for (Map.Entry<String, ArrayPostings.Builder> e : values.entrySet()) {
            if (p.matcher(e.getKey()).matches()) {
                result = Postings.union(result, e.getValue().snapshot());
            }
        }
        return result;
    }

    // Серии, у которых лейбла name нет вовсе (значение "").
    private Postings withoutLabel(String name) {
        Postings has = ArrayPostings.EMPTY;
        for (ArrayPostings.Builder b : postings.getOrDefault(name, Map.of()).values()) {
            has = Postings.union(has, b.snapshot());
        }
        return Postings.without(all.snapshot(), has);
    }
}
