package org.tsdb.db;

import java.util.List;
import java.util.Set;

import org.tsdb.model.Labels;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;

/**
 * Фасад БД, за ним спрятаны head, WAL, блоки, индекс и движок запросов.
 * В лабе 3 его обернёт gRPC-сервер, в лабах 4-5 тот же контракт реализуют реплика и шард-роутер.
 *   Реализации потокобезопасны;
 *   время везде long, миллисекунды UTC;
 *   null не принимаем и не возвращаем;
 *   исключения только unchecked, I/O — UncheckedIOException;
 *   любой метод после close() — IllegalStateException.
 */
public interface TimeSeriesDB extends AutoCloseable {
    // ts строго больше последнего у этой серии, иначе OutOfOrderSampleException
    void append(Labels labels, long timestamp, double value);

    // Один fsync на весь батч. Не атомарен: точки до ошибочной остаются записанными.
    void appendBatch(List<SampleInput> batch);

    QueryResult query(Query query);

    Set<String> labelNames();

    Set<String> labelValues(String name);

    // Сбросить head в блок немедленно, не дожидаясь blockRangeMs.
    void flush();

    // Один проход компакции; обычно это делает фоновая задача.
    void compact();

    // Удалить блоки целиком старше timestamp; отдельные точки не удаляем.
    void deleteBefore(long timestamp);

    DbStats stats();

    // Данные не теряются: что осталось в head, поднимется из WAL. Повторный вызов — no-op.
    @Override
    void close();

    static TimeSeriesDB open(DbOptions options) {
        return LocalTimeSeriesDB.open(options);
    }
}
