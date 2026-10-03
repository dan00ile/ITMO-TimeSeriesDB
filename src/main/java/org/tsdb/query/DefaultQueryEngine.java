package org.tsdb.query;

import java.util.List;

import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;

// Локальный движок запросов.
public final class DefaultQueryEngine implements QueryEngine {
    @Override
    public QueryResult execute(Query query, List<Queryable> sources) {
        throw new UnsupportedOperationException("lab2");
    }
}
