package org.tsdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.tsdb.db.DbOptions;

class ServerConfigTest {

    @Test
    void emptyEnvGivesDefaults() {
        ServerConfig cfg = ServerConfig.fromEnv(Map.of());
        assertEquals(ServerConfig.DEFAULT_PORT, cfg.port());
        assertEquals(Path.of(ServerConfig.DEFAULT_DATA_DIR), cfg.dataDir());

        DbOptions defaults = DbOptions.defaults(cfg.dataDir());
        assertEquals(defaults, cfg.toDbOptions());
    }

    @Test
    void envOverridesValues() {
        ServerConfig cfg = ServerConfig.fromEnv(Map.of(
                "TSDB_PORT", "5555",
                "TSDB_DATA_DIR", "/data",
                "TSDB_BLOCK_RANGE_MS", "60000",
                "TSDB_RETENTION_MS", "0",
                "TSDB_WAL_SYNC_INTERVAL_MS", "0",
                "TSDB_COMPACTION_INTERVAL_MS", "1000"));
        assertEquals(5555, cfg.port());
        assertEquals(Path.of("/data"), cfg.dataDir());

        DbOptions o = cfg.toDbOptions();
        assertEquals(Path.of("/data").toAbsolutePath(), o.dataDir());
        assertEquals(60_000, o.blockRangeMs());
        assertEquals(0, o.retentionMs());
        assertEquals(0, o.walSyncIntervalMs());
        assertEquals(1_000, o.compactionIntervalMs());
        assertEquals(DbOptions.DEFAULT_CHUNK_MAX_SAMPLES, o.chunkMaxSamples());
    }

    @Test
    void blankValueMeansDefault() {
        ServerConfig cfg = ServerConfig.fromEnv(Map.of("TSDB_PORT", " ", "TSDB_DATA_DIR", ""));
        assertEquals(ServerConfig.DEFAULT_PORT, cfg.port());
        assertEquals(Path.of(ServerConfig.DEFAULT_DATA_DIR), cfg.dataDir());
    }

    @Test
    void errorNamesTheVariable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.fromEnv(Map.of("TSDB_BLOCK_RANGE_MS", "2h")));
        assertTrue(e.getMessage().contains("TSDB_BLOCK_RANGE_MS"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromEnv(Map.of("TSDB_PORT", "abc")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromEnv(Map.of("TSDB_PORT", "99999999999")));
    }

    @Test
    void invalidValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromEnv(Map.of("TSDB_PORT", "70000")));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromEnv(Map.of("TSDB_PORT", "-1")));
        // значения БД проверяет DbOptions
        ServerConfig cfg = ServerConfig.fromEnv(Map.of("TSDB_BLOCK_RANGE_MS", "0"));
        assertThrows(IllegalArgumentException.class, cfg::toDbOptions);
    }
}
