
package org.tsdb.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.wal.WalPosition;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyStateReturnsEmpty() {
        ReplicationStateStore store =
                new ReplicationStateStore(tempDir);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void savesAndLoadsPosition() {
        ReplicationStateStore store =
                new ReplicationStateStore(tempDir);

        WalPosition expected = new WalPosition(3, 1024);

        store.save(expected);

        assertEquals(expected, store.load().orElseThrow());
    }

    @Test
    void stateSurvivesRestart() {
        WalPosition expected = new WalPosition(7, 2048);

        ReplicationStateStore first =
                new ReplicationStateStore(tempDir);

        first.save(expected);

        // Имитируем перезапуск: создаём новый объект.
        ReplicationStateStore reopened =
                new ReplicationStateStore(tempDir);

        assertEquals(
                expected,
                reopened.load().orElseThrow()
        );
    }

    @Test
    void newPositionReplacesOldPosition() {
        ReplicationStateStore store =
                new ReplicationStateStore(tempDir);

        store.save(new WalPosition(1, 100));
        store.save(new WalPosition(2, 200));

        assertEquals(
                new WalPosition(2, 200),
                store.load().orElseThrow()
        );

        assertFalse(Files.exists(
                tempDir.resolve("replication.state.tmp")));
    }

    @Test
    void rejectsInvalidPosition() {
        ReplicationStateStore store =
                new ReplicationStateStore(tempDir);

        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(new WalPosition(-1, 0))
        );
    }

    @Test
    void corruptedStateIsRejected() throws Exception {
        Files.write(
                tempDir.resolve("replication.state"),
                new byte[]{1, 2, 3}
        );

        ReplicationStateStore store =
                new ReplicationStateStore(tempDir);

        assertThrows(
                java.io.UncheckedIOException.class,
                store::load
        );
    }
}
