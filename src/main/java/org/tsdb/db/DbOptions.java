package org.tsdb.db;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Настройки БД, задаются один раз при открытии. Значения по умолчанию — «как в проде»,
 * в тестах окна измеряются секундами.
 *   walSyncIntervalMs == 0 — fsync на каждый append;
 *   retentionMs == 0 — хранить вечно, удаление только через deleteBefore;
 *   compactionIntervalMs == 0 — фоновая компакция выключена.
 * dataDir нормализуется, чтобы equals не зависел от "data" против "./data".
 */
public record DbOptions(
        Path dataDir,
        long blockRangeMs,
        long retentionMs,
        long walSyncIntervalMs,
        int chunkMaxSamples,
        long compactionIntervalMs
) {
    public static final long DEFAULT_BLOCK_RANGE_MS = 2 * 60 * 60 * 1000L;
    public static final long DEFAULT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    public static final long DEFAULT_WAL_SYNC_INTERVAL_MS = 100;
    public static final int DEFAULT_CHUNK_MAX_SAMPLES = 120;
    public static final long DEFAULT_COMPACTION_INTERVAL_MS = 60_000;

    public DbOptions {
        dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        requirePositive("blockRangeMs", blockRangeMs);
        requireNonNegative("retentionMs", retentionMs);
        requireNonNegative("walSyncIntervalMs", walSyncIntervalMs);
        requirePositive("chunkMaxSamples", chunkMaxSamples);
        requireNonNegative("compactionIntervalMs", compactionIntervalMs);
        if (retentionMs != 0 && retentionMs < blockRangeMs) {
            // блок устаревал бы раньше, чем его успели записать
            throw new IllegalArgumentException(
                    "retentionMs < blockRangeMs: " + retentionMs + " < " + blockRangeMs);
        }
    }

    public static DbOptions defaults(Path dataDir) {
        return builder(dataDir).build();
    }

    public static Builder builder(Path dataDir) {
        return new Builder(dataDir);
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got " + value);
        }
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    public static final class Builder {
        private final Path dataDir;
        private long blockRangeMs = DEFAULT_BLOCK_RANGE_MS;
        private long retentionMs = DEFAULT_RETENTION_MS;
        private long walSyncIntervalMs = DEFAULT_WAL_SYNC_INTERVAL_MS;
        private int chunkMaxSamples = DEFAULT_CHUNK_MAX_SAMPLES;
        private long compactionIntervalMs = DEFAULT_COMPACTION_INTERVAL_MS;

        private Builder(Path dataDir) {
            this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        }

        public Builder blockRangeMs(long v) {
            this.blockRangeMs = v;
            return this;
        }

        public Builder retentionMs(long v) {
            this.retentionMs = v;
            return this;
        }

        public Builder walSyncIntervalMs(long v) {
            this.walSyncIntervalMs = v;
            return this;
        }

        public Builder chunkMaxSamples(int v) {
            this.chunkMaxSamples = v;
            return this;
        }

        public Builder compactionIntervalMs(long v) {
            this.compactionIntervalMs = v;
            return this;
        }

        public DbOptions build() {
            return new DbOptions(
                    dataDir, blockRangeMs, retentionMs, walSyncIntervalMs,
                    chunkMaxSamples, compactionIntervalMs);
        }
    }
}
