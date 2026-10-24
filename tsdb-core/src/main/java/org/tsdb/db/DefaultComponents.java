package org.tsdb.db;

import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.block.FileBlockReader;
import org.tsdb.block.FileBlockWriter;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.LeveledCompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.compact.TimeRetentionPolicy;
import org.tsdb.encoding.GorillaCodec;
import org.tsdb.head.Head;
import org.tsdb.head.MemHead;
import org.tsdb.query.DefaultQueryEngine;
import org.tsdb.query.QueryEngine;
import org.tsdb.wal.FileWal;
import org.tsdb.wal.Wal;

// Настоящие реализации слоёв для TimeSeriesDB.open(options).
final class DefaultComponents implements DbComponents {

    @Override
    public Wal openWal(DbOptions options) {
        return new FileWal(options);
    }

    @Override
    public Head newHead(DbOptions options) {
        return new MemHead(new GorillaCodec(options.chunkMaxSamples()));
    }

    @Override
    public BlockWriter blockWriter(DbOptions options) {
        return new FileBlockWriter(options.chunkMaxSamples());
    }

    @Override
    public BlockReader blockReader(DbOptions options) {
        return new FileBlockReader();
    }

    @Override
    public QueryEngine queryEngine(DbOptions options) {
        return new DefaultQueryEngine();
    }

    @Override
    public CompactionPlanner compactionPlanner(DbOptions options) {
        return new LeveledCompactionPlanner();
    }

    @Override
    public RetentionPolicy retentionPolicy(DbOptions options) {
        return new TimeRetentionPolicy(options.retentionMs());
    }
}
