package org.tsdb.testing;

import java.util.ArrayList;
import java.util.List;

import org.tsdb.block.BlockMeta;
import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.db.DbComponents;
import org.tsdb.db.DbOptions;
import org.tsdb.db.DbPaths;
import org.tsdb.head.Head;
import org.tsdb.query.QueryEngine;
import org.tsdb.wal.Wal;

// Простые слои A и B в памяти — чтобы проверять фасад, пока настоящих реализаций нет.
// Компакция сливает все блоки в один, retention удаляет блоки старше now - retentionMs.
public final class FakeComponents implements DbComponents {

    private FakeWal wal;

    // WAL последней открытой БД — посмотреть, сколько было fsync и закрыт ли он.
    public FakeWal wal() {
        return wal;
    }

    @Override
    public Wal openWal(DbOptions options) {
        wal = new FakeWal(DbPaths.of(options).walDir());
        return wal;
    }

    @Override
    public Head newHead(DbOptions options) {
        return new FakeHead();
    }

    @Override
    public BlockWriter blockWriter(DbOptions options) {
        return new FakeBlocks();
    }

    @Override
    public BlockReader blockReader(DbOptions options) {
        return new FakeBlocks();
    }

    @Override
    public QueryEngine queryEngine(DbOptions options) {
        return new FakeQueryEngine();
    }

    @Override
    public CompactionPlanner compactionPlanner(DbOptions options) {
        return blocks -> blocks.size() < 2 ? List.of() : List.of(blocks);
    }

    @Override
    public RetentionPolicy retentionPolicy(DbOptions options) {
        return (blocks, now) -> {
            List<BlockMeta> expired = new ArrayList<>();
            for (BlockMeta meta : blocks) {
                if (meta.maxTime() < now - options.retentionMs()) {
                    expired.add(meta);
                }
            }
            return expired;
        };
    }
}
