package org.tsdb.query;

import java.util.List;

import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;

// Выполняет запрос над набором источников. Состояния между запросами не хранит.
// в каждом источнике: select -> labels -> chunks
public interface QueryEngine {
    // в sources уже отобранные по времени источники, в порядке возрастания времени
    QueryResult execute(Query query, List<Queryable> sources);
}
