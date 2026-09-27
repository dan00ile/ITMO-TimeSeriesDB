package org.tsdb.head;

import java.util.List;
import java.util.Optional;

import org.tsdb.encoding.Chunk;
import org.tsdb.index.InvertedIndex;
import org.tsdb.index.Postings;
import org.tsdb.index.SeriesRegistry;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.query.Queryable;

// Последние ~2 часа данных Регистрацию серии и запись в WAL делает фасад, head про WAL не знает.
public interface Head extends Queryable {
    @Override
    long minTime();

    @Override
    long maxTime();

    SeriesRegistry registry();

    InvertedIndex index();

    // Дописать точку в открытый chunk серии; полный chunk закрывается, открывается новый.
    void append(long seriesId, long timestamp, double value);

    // Все chunk'и серии, пересекающиеся с [start, end]
    @Override
    List<Chunk> chunks(long seriesId, long start, long end);

    // Отрезать всё, что  < upTo в виде набора данных для BlockWriter
    HeadSnapshot truncate(long upTo);

    @Override
    default Postings select(List<Matcher> matchers) {
        return index().select(matchers);
    }

    @Override
    default Optional<Labels> labels(long id) {
        return registry().labels(id);
    }
}
