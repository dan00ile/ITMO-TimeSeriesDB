package org.tsdb.testing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.tsdb.encoding.Chunk;
import org.tsdb.index.PostingsIterator;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Labels;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;
import org.tsdb.query.QueryEngine;
import org.tsdb.query.Queryable;

// Только сырые точки: склеить серии из всех источников по Labels и отрезать по времени.
// Источники не сортирует — если фасад передаст их не по времени, точки в ответе перепутаются.
public final class FakeQueryEngine implements QueryEngine {

    @Override
    public QueryResult execute(Query query, List<Queryable> sources) {
        if (query.aggregation() != Aggregation.NONE) {
            throw new UnsupportedOperationException("fake engine: only raw queries");
        }
        Map<Labels, List<Sample>> found = new TreeMap<>(Comparator.comparing(Labels::canonical));
        for (Queryable source : sources) {
            PostingsIterator it = source.select(query.matchers()).iterator();
            while (it.next()) {
                long id = it.at();
                List<Sample> samples = found.computeIfAbsent(source.labels(id).orElseThrow(), l -> new ArrayList<>());
                for (Chunk chunk : source.chunks(id, query.start(), query.end())) {
                    for (Sample s : ListChunk.samplesOf(chunk)) {
                        if (s.timestamp() >= query.start() && s.timestamp() <= query.end()) {
                            samples.add(s);
                        }
                    }
                }
            }
        }
        List<SeriesResult> result = new ArrayList<>();
        for (Map.Entry<Labels, List<Sample>> e : found.entrySet()) {
            if (!e.getValue().isEmpty()) {
                result.add(new SeriesResult(e.getKey(), e.getValue()));
            }
        }
        return new QueryResult(result);
    }
}
