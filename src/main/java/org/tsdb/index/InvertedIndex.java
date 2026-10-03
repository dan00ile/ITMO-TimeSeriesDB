package org.tsdb.index;

import java.util.List;
import java.util.Set;

import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// Инвертированный индекс
public interface InvertedIndex {
    // Добавить серию. Id должен быть больше всех добавленных ранее
    void add(long id, Labels labels);

    // Серии с лейблом name="value"
    Postings postings(String name, String value);

    Set<String> labelNames();

    Set<String> labelValues(String name);

    /**
     * Главный метод: применяет все matchers, возвращает пересечение.
     *  Eq — postings(String, String)
     *  Regex union postings всех подходящих значений из labelValues
     *  NotEq NotRegex — without из результата позитивных matchers.
     */
    Postings select(List<Matcher> matchers);
}
