package org.tsdb.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.tsdb.encoding.Chunk;
import org.tsdb.encoding.SampleIterator;
import org.tsdb.index.Postings;
import org.tsdb.index.PostingsIterator;
import org.tsdb.model.Aggregation;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Sample;
import org.tsdb.model.SeriesResult;

// Локальный движок запросов. Потокобезопасен: состояния нет.
public final class DefaultQueryEngine implements QueryEngine {

    @Override
    public QueryResult execute(Query query, List<Queryable> sources) {
        Map<Labels, List<Sample>> bySeries = collect(query, sources);
        Map<Labels, List<Sample>> result = query.aggregation() == Aggregation.NONE
                ? bySeries
                : aggregate(query, bySeries);
        return new QueryResult(result.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .sorted(Comparator.comparing(e -> e.getKey().canonical()))
                .map(e -> new SeriesResult(e.getKey(), e.getValue()))
                .toList());
    }

    // Сырые точки в [start, end] по каждой серии, склеенные из всех источников.
    private static Map<Labels, List<Sample>> collect(Query query, List<Queryable> sources) {
        long start = query.start();
        long end = query.end();
        Map<Labels, List<Sample>> bySeries = new HashMap<>();
        for (Queryable src : sources) {
            if (src.maxTime() < start || src.minTime() > end) {
                continue;
            }
            Postings postings = src.select(query.matchers());
            PostingsIterator it = postings.iterator();
            while (it.next()) {
                long id = it.at();
                Labels labels = src.labels(id).orElseThrow(
                        () -> new IllegalStateException("source returned unknown series id " + id));
                List<Sample> samples = bySeries.computeIfAbsent(labels, l -> new ArrayList<>());
                for (Chunk chunk : src.chunks(id, start, end)) {
                    SampleIterator s = chunk.iterator();
                    while (s.next()) {
                        long ts = s.timestamp();
                        if (ts > end) {
                            break; // точки в chunk'е по возрастанию — хвост не распаковываем
                        }
                        if (ts >= start) {
                            samples.add(new Sample(ts, s.value()));
                        }
                    }
                }
            }
        }
        bySeries.values().forEach(DefaultQueryEngine::sortAndDedupe);
        return bySeries;
    }

    // Источники обычно не пересекаются по времени, и конкатенация уже отсортирована.
    // Если нет (порядок источников, повтор точки после компакции) — сортируем и оставляем первую точку на ts.
    private static void sortAndDedupe(List<Sample> samples) {
        boolean ordered = true;
        for (int i = 1; i < samples.size() && ordered; i++) {
            ordered = samples.get(i - 1).timestamp() < samples.get(i).timestamp();
        }
        if (ordered) {
            return;
        }
        samples.sort(Comparator.comparingLong(Sample::timestamp)); // стабильная: первая точка остаётся первой
        int w = 0;
        for (int r = 0; r < samples.size(); r++) {
            if (w == 0 || samples.get(w - 1).timestamp() != samples.get(r).timestamp()) {
                samples.set(w++, samples.get(r));
            }
        }
        samples.subList(w, samples.size()).clear();
    }

    private static Map<Labels, List<Sample>> aggregate(Query query, Map<Labels, List<Sample>> bySeries) {
        // группа -> bucket -> накопитель; TreeMap, чтобы точки вышли по возрастанию ts
        Map<Labels, TreeMap<Long, Aggregator>> groups = new HashMap<>();
        for (Map.Entry<Labels, List<Sample>> e : bySeries.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            TreeMap<Long, Aggregator> buckets = groups.computeIfAbsent(groupKey(e.getKey(), query.groupBy()),
                    k -> new TreeMap<>());
            for (Sample s : e.getValue()) {
                long ts = query.step() == 0
                        ? query.end()
                        : query.start() + (s.timestamp() - query.start()) / query.step() * query.step();
                buckets.computeIfAbsent(ts, t -> Aggregator.of(query.aggregation())).add(s.value());
            }
        }
        Map<Labels, List<Sample>> result = new HashMap<>();
        groups.forEach((group, buckets) -> result.put(group, buckets.entrySet().stream()
                .map(b -> new Sample(b.getKey(), b.getValue().result()))
                .toList()));
        return result;
    }

    // Лейблы серии из groupBy; отсутствующие у серии пропускаются.
    private static Labels groupKey(Labels labels, List<String> groupBy) {
        List<Label> key = new ArrayList<>(groupBy.size());
        for (String name : groupBy.stream().distinct().toList()) {
            labels.get(name).ifPresent(v -> key.add(new Label(name, v)));
        }
        return new Labels(key);
    }
}
