package org.tsdb.cluster;

/** Стык с хранилищем replication.state из части A. Методы могут бросать UncheckedIOException. */
public interface FollowerCheckpoint {
    /** Отсутствующий файл означает {@link ReplicationProgress#empty()}. */
    ReplicationProgress load();

    /** Атомарно сохранить снимок. FollowerLoop уже вызвал локальный wal.sync(). */
    void save(ReplicationProgress progress);
}
