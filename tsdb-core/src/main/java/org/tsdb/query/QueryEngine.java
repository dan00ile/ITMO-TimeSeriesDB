package org.tsdb.query;

import java.util.List;

import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;

// Выполняет запрос над набором источников. Состояния между запросами не хранит.
// в каждом источнике: select -> labels -> chunks; серии из разных источников склеиваются по Labels, не по id.
// Агрегация: ключ группы — лейблы из groupBy, которые есть у серии (пустой groupBy — одна группа).
// step == 0 — одна точка на группу с ts = query.end; step > 0 — точка на окно с ts = start + bucket * step.
public interface QueryEngine {
    // в sources уже отобранные по времени источники, в порядке возрастания времени
    QueryResult execute(Query query, List<Queryable> sources);
}
