package org.tsdb.index;

import java.util.List;
import java.util.Set;

import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// Инвертированный индекс
public interface InvertedIndex {
    // Добавить серию. Id должен быть больше всех добавленных ранее: postings — дописываемый long[] без сортировки.
    // Нарушение — IllegalArgumentException, а не тихая порча postings. Следствия:
    //  - checkpoint WAL хранит серии по возрастанию id, replay вызывает add в этом порядке;
    //  - id внутри блока назначаются заново: 1..n в порядке Labels.canonical(). BlockWriter.write и merge
    //    строят индекс в том же порядке. Id head'а в блок не переносятся (QueryEngine склеивает по Labels).
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
