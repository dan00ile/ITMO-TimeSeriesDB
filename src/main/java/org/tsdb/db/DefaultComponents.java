package org.tsdb.db;

import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.encoding.ChunkCodec;
import org.tsdb.head.Head;
import org.tsdb.head.MemHead;
import org.tsdb.query.DefaultQueryEngine;
import org.tsdb.query.QueryEngine;
import org.tsdb.wal.Wal;

// Настоящие реализации слоёв для TimeSeriesDB.open(options). Пока A и B их не влили — UnsupportedOperationException.
final class DefaultComponents implements DbComponents {

    @Override
    public Wal openWal(DbOptions options) {
        throw notReady("Wal", "A");
    }

    @Override
    public Head newHead(DbOptions options) {
        return new MemHead(chunkCodec(options));
    }

    @Override
    public BlockWriter blockWriter(DbOptions options) {
        throw notReady("BlockWriter", "A");
    }

    @Override
    public BlockReader blockReader(DbOptions options) {
        throw notReady("BlockReader", "A");
    }

    @Override
    public QueryEngine queryEngine(DbOptions options) {
        return new DefaultQueryEngine();
    }

    @Override
    public CompactionPlanner compactionPlanner(DbOptions options) {
        throw notReady("CompactionPlanner", "A");
    }

    @Override
    public RetentionPolicy retentionPolicy(DbOptions options) {
        throw notReady("RetentionPolicy", "A");
    }

    // Gorilla с лимитом options.chunkMaxSamples() точек на chunk.
    private static ChunkCodec chunkCodec(DbOptions options) {
        throw notReady("ChunkCodec (Gorilla)", "A");
    }

    private static UnsupportedOperationException notReady(String what, String owner) {
        return new UnsupportedOperationException("lab2: нет реализации " + what + " (участник " + owner + ")");
    }
}
