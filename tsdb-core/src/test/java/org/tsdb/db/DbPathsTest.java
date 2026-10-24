package org.tsdb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbPathsTest {

    @TempDir
    Path dir;

    @Test
    void layoutIsFixed() {
        DbPaths paths = DbPaths.of(DbOptions.defaults(dir));
        assertEquals(dir.resolve("wal"), paths.walDir());
        assertEquals(dir.resolve("blocks"), paths.blocksDir());
        assertEquals(dir.resolve("tmp"), paths.tmpDir());
        assertEquals(dir.resolve("blocks").resolve("01HF7"), paths.blockDir("01HF7"));
    }
}
