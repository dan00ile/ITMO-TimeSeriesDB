package org.tsdb.cluster;

import java.util.Objects;
import org.tsdb.db.LocalTimeSeriesDB;

/** Подключает файловое состояние части A к follower loop, сохраняя существующий формат файла. */
public final class StoredFollowerCheckpoint implements FollowerCheckpoint {
    private final LocalTimeSeriesDB db;
    private final ReplicationStateStore store;

    public StoredFollowerCheckpoint(LocalTimeSeriesDB db) {
        this.db = Objects.requireNonNull(db, "db");
        this.store = new ReplicationStateStore(db.paths().dataDir());
    }

    @Override public ReplicationProgress load() {
        return store.load()
                .map(position -> new ReplicationProgress(position, db.maxTimestamp()))
                .orElseGet(ReplicationProgress::empty);
    }

    /** FollowerLoop обязан выполнить локальный wal.sync() до этого вызова. */
    @Override public void save(ReplicationProgress progress) {
        Objects.requireNonNull(progress, "progress");
        store.save(Objects.requireNonNull(progress.applied(), "progress.applied()"));
    }
}
