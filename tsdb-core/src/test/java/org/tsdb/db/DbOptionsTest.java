package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbOptionsTest {

    @TempDir
    Path dir;

    @Test
    void defaultsAreProductionValues() {
        DbOptions o = DbOptions.defaults(dir);
        assertEquals(DbOptions.DEFAULT_BLOCK_RANGE_MS, o.blockRangeMs());
        assertEquals(DbOptions.DEFAULT_RETENTION_MS, o.retentionMs());
        assertEquals(DbOptions.DEFAULT_WAL_SYNC_INTERVAL_MS, o.walSyncIntervalMs());
        assertEquals(DbOptions.DEFAULT_CHUNK_MAX_SAMPLES, o.chunkMaxSamples());
        assertEquals(DbOptions.DEFAULT_COMPACTION_INTERVAL_MS, o.compactionIntervalMs());
    }

    @Test
    void builderOverridesOnlyWhatIsAsked() {
        DbOptions o = DbOptions.builder(dir).blockRangeMs(1_000).chunkMaxSamples(4).build();
        assertEquals(1_000, o.blockRangeMs());
        assertEquals(4, o.chunkMaxSamples());
        assertEquals(DbOptions.DEFAULT_WAL_SYNC_INTERVAL_MS, o.walSyncIntervalMs());
    }

    @Test
    void dataDirIsNormalized() {
        DbOptions o = DbOptions.defaults(dir.resolve("data").resolve("..").resolve("data"));
        assertEquals(dir.resolve("data"), o.dataDir());
    }

    @Test
    void zeroMeansManualOrEveryAppend() {
        DbOptions o = DbOptions.builder(dir)
                .retentionMs(0)
                .walSyncIntervalMs(0)
                .compactionIntervalMs(0)
                .build();
        assertEquals(0, o.retentionMs());
        assertEquals(0, o.walSyncIntervalMs());
        assertEquals(0, o.compactionIntervalMs());
    }

    @Test
    void invalidValuesRejected() {
        assertThrows(NullPointerException.class, () -> DbOptions.defaults(null));
        assertThrows(IllegalArgumentException.class, () -> DbOptions.builder(dir).blockRangeMs(0).build());
        assertThrows(IllegalArgumentException.class, () -> DbOptions.builder(dir).chunkMaxSamples(0).build());
        assertThrows(IllegalArgumentException.class, () -> DbOptions.builder(dir).walSyncIntervalMs(-1).build());
    }

    @Test
    void retentionShorterThanOneBlockRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DbOptions.builder(dir).blockRangeMs(2_000).retentionMs(1_000).build());
    }
}
