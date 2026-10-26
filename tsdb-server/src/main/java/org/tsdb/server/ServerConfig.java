package org.tsdb.server;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.tsdb.db.DbOptions;

/**
 * Настройки сервера из переменных окружения: TSDB_PORT, TSDB_DATA_DIR, TSDB_BLOCK_RANGE_MS,
 * TSDB_RETENTION_MS, TSDB_WAL_SYNC_INTERVAL_MS, TSDB_COMPACTION_INTERVAL_MS, TSDB_HTTP_PORT.
 * Не заданная или пустая переменная — значение по умолчанию; длительности в миллисекундах,
 * 0 значит то же, что в DbOptions. Остальные значения проверяет DbOptions в toDbOptions().
 */
public record ServerConfig(
        int port,
        Path dataDir,
        long blockRangeMs,
        long retentionMs,
        long walSyncIntervalMs,
        long compactionIntervalMs,
        // HTTP для Prometheus remote read (/api/v1/read); 0 — выключен
        int httpPort
) {
    public static final int DEFAULT_PORT = 2222;
    public static final String DEFAULT_DATA_DIR = "data";

    public ServerConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        // 0 — любой свободный порт, удобно в тестах
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535, got " + port);
        }
        if (httpPort < 0 || httpPort > 65535) {
            throw new IllegalArgumentException("httpPort must be in 0..65535, got " + httpPort);
        }
    }

    public static ServerConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static ServerConfig fromEnv(Map<String, String> env) {
        return new ServerConfig(
                readInt(env, "TSDB_PORT", DEFAULT_PORT),
                Path.of(readString(env, "TSDB_DATA_DIR", DEFAULT_DATA_DIR)),
                readLong(env, "TSDB_BLOCK_RANGE_MS", DbOptions.DEFAULT_BLOCK_RANGE_MS),
                readLong(env, "TSDB_RETENTION_MS", DbOptions.DEFAULT_RETENTION_MS),
                readLong(env, "TSDB_WAL_SYNC_INTERVAL_MS", DbOptions.DEFAULT_WAL_SYNC_INTERVAL_MS),
                readLong(env, "TSDB_COMPACTION_INTERVAL_MS", DbOptions.DEFAULT_COMPACTION_INTERVAL_MS),
                readInt(env, "TSDB_HTTP_PORT", 0));
    }

    public DbOptions toDbOptions() {
        return DbOptions.builder(dataDir)
                .blockRangeMs(blockRangeMs)
                .retentionMs(retentionMs)
                .walSyncIntervalMs(walSyncIntervalMs)
                .compactionIntervalMs(compactionIntervalMs)
                .build();
    }

    private static String readString(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int readInt(Map<String, String> env, String name, int defaultValue) {
        String value = readString(env, name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got \"" + value + "\"");
        }
    }

    private static long readLong(Map<String, String> env, String name, long defaultValue) {
        String value = readString(env, name, String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got \"" + value + "\"");
        }
    }
}
