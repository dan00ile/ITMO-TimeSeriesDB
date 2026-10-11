package org.tsdb.db;

import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.head.Head;
import org.tsdb.query.QueryEngine;
import org.tsdb.wal.Wal;

// Из каких реализаций слоёв собирается LocalTimeSeriesDB. Фасад видит только интерфейсы,
// конкретные классы A и B подставляются в DefaultComponents (или свои — в тестах).
public interface DbComponents {
    // WAL в DbPaths.of(options).walDir(), каталог к этому моменту уже создан
    Wal openWal(DbOptions options);

    Head newHead(DbOptions options);

    BlockWriter blockWriter(DbOptions options);

    BlockReader blockReader(DbOptions options);

    QueryEngine queryEngine(DbOptions options);

    CompactionPlanner compactionPlanner(DbOptions options);

    RetentionPolicy retentionPolicy(DbOptions options);
}
