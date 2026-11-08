package org.tsdb.model;

import java.util.List;
import java.util.Objects;

/**
 * Запрос: выбрать серии по matchers в диапазоне [start, end], опционально агрегировать.
 * matchers должен быть хотя бы один Matcher.Eq (обычно на __name__):
 *   groupBy это лейблы, по которым группируем; если пусто, то все серии в одну группу.
 *   step это агрегация по окнам, начиная со start;
 *   При aggregation == NONE groupBy должен быть пуст, а step == 0
 */
public record Query(
        List<Matcher> matchers,
        long start,
        long end,
        Aggregation aggregation,
        List<String> groupBy,
        long step
) {
    public Query {
        matchers = List.copyOf(Objects.requireNonNull(matchers, "matchers"));
        groupBy = List.copyOf(Objects.requireNonNull(groupBy, "groupBy"));
        Objects.requireNonNull(aggregation, "aggregation");
        if (end < start) {
            throw new IllegalArgumentException("end < start: " + end + " < " + start);
        }
        if (matchers.stream().noneMatch(m -> m instanceof Matcher.Eq)) {
            throw new IllegalArgumentException("query needs at least one Eq matcher");
        }
        if (step < 0) {
            throw new IllegalArgumentException("step < 0: " + step);
        }
        if (aggregation == Aggregation.NONE && (!groupBy.isEmpty() || step != 0)) {
            throw new IllegalArgumentException("groupBy/step require aggregation");
        }
    }

    // Сырые точки без агрегации
    public static Query range(List<Matcher> matchers, long start, long end) {
        return new Query(matchers, start, end, Aggregation.NONE, List.of(), 0);
    }

    // Та же выборка, но с агрегацией
    public Query aggregate(Aggregation aggregation, List<String> groupBy, long step) {
        return new Query(matchers, start, end, aggregation, groupBy, step);
    }
}
