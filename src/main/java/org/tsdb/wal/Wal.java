package org.tsdb.wal;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Write-Ahead Log.
 *
 * Используется для восстановления свежих данных
 * после аварийного завершения процесса.
 */
public interface Wal extends AutoCloseable {

    /**
     * Дописать запись.
     *
     * @return позиция записанной записи
     */
    WalPosition append(WalRecord record);

    /**
     * Принудительно сбросить текущий сегмент на диск.
     */
    void sync();

    /**
     * Проиграть WAL при запуске БД.
     */
    void replay(Consumer<WalRecord> handler);

    /**
     * Удалить старые сегменты WAL.
     */
    void truncateBefore(long timestamp);

    /**
     * Читать записи начиная с указанной позиции.
     *
     * Stream необходимо закрыть вызывающему коду.
     */
    Stream<WalEntry> readFrom(WalPosition position);

    @Override
    void close();
}