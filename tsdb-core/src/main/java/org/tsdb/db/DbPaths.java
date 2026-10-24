package org.tsdb.db;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Раскладка каталога данных. Что лежит внутри блока или сегмента — зона A, где они лежат — здесь.
 * <pre>
 * dataDir/
 *   wal/        сегменты журнала: 00000001, 00000002, ...
 *   blocks/     каталог на блок, имя — ULID: meta.json, index, chunks.bin
 *   tmp/        недописанные блоки; готовый переезжает в blocks/ через ATOMIC_MOVE
 * </pre>
 */
public record DbPaths(Path dataDir) {
    public static final String WAL_DIR = "wal";
    public static final String BLOCKS_DIR = "blocks";
    public static final String TMP_DIR = "tmp";

    public DbPaths {
        Objects.requireNonNull(dataDir, "dataDir");
    }

    public static DbPaths of(DbOptions options) {
        return new DbPaths(Objects.requireNonNull(options, "options").dataDir());
    }

    public Path walDir() {
        return dataDir.resolve(WAL_DIR);
    }

    public Path blocksDir() {
        return dataDir.resolve(BLOCKS_DIR);
    }

    public Path blockDir(String ulid) {
        return blocksDir().resolve(Objects.requireNonNull(ulid, "ulid"));
    }

    public Path tmpDir() {
        return dataDir.resolve(TMP_DIR);
    }
}
