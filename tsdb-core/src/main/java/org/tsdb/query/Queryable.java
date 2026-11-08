package org.tsdb.query;

import java.util.List;
import java.util.Optional;

import org.tsdb.encoding.Chunk;
import org.tsdb.index.Postings;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;

// Источник данных для запросов. Реализуют Head, Block и RemoteQueryable
public interface Queryable {
    long minTime();

    long maxTime();

    Postings select(List<Matcher> matchers);

    Optional<Labels> labels(long id);

    List<Chunk> chunks(long id, long start, long end);
}
