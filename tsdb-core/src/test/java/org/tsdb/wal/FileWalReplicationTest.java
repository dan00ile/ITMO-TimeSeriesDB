
package org.tsdb.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.Optional;

class FileWalReplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void listenerReceivesAppendedPositions() {
        List<WalPosition> notified = new ArrayList<>();

        try (FileWal wal = new FileWal(tempDir)) {
            wal.setAppendListener(notified::add);

            WalPosition first = wal.append(sample(1000));
            WalPosition second = wal.append(sample(2000));

            assertEquals(List.of(first, second), notified);
        }
    }

    @Test
    void readFromReturnsRecordsStartingAtPosition() {
        try (FileWal wal = new FileWal(tempDir)) {
            wal.append(sample(1000));
            WalPosition second = wal.append(sample(2000));
            wal.append(sample(3000));

            try (var entries = wal.readFrom(second)) {
                List<WalEntry> result = entries.toList();

                assertEquals(2, result.size());
                assertEquals(second, result.get(0).position());
            }
        }
    }

    @Test
    void readFromWorksAcrossSegments() {
        // Маленький размер сегмента заставляет WAL
        // создавать новые файлы при записи.
        try (FileWal wal = new FileWal(tempDir, 64, 0)) {
            wal.append(sample(1000));
            WalPosition second = wal.append(sample(2000));
            WalPosition third = wal.append(sample(3000));

            assertTrue(third.segment() > second.segment());

            try (var entries = wal.readFrom(second)) {
                List<WalEntry> result = entries.toList();

                assertEquals(2, result.size());
                assertEquals(second, result.get(0).position());
                assertEquals(third, result.get(1).position());
            }
        }
    }

    @Test
    void invalidRecordBoundaryIsRejected() {
        try (FileWal wal = new FileWal(tempDir)) {
            WalPosition valid = wal.append(sample(1000));

            WalPosition invalid = new WalPosition(
                    valid.segment(), valid.offset() + 1);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> wal.readFrom(invalid));
        }
    }

    private static WalRecord sample(long timestamp) {
        return new WalRecord.Samples(
                List.of(new SampleEntry(1, timestamp, 42.0)));
    }


    @Test
    void retentionFloorProtectsRequiredSegments() {
        try (FileWal wal = new FileWal(tempDir, 64, 0)) {
            wal.append(sample(1000));
            WalPosition second = wal.append(sample(2000));
            wal.append(sample(3000));

            // Фолловеру ещё нужен второй сегмент.
            wal.setWalRetentionFloor(
                    () -> Optional.of(second)
            );

            wal.truncateBefore(2500);

            assertFalse(Files.exists(
                    tempDir.resolve("00000001")));

            assertTrue(Files.exists(
                    tempDir.resolve("00000002")));

            assertTrue(Files.exists(
                    tempDir.resolve("00000003")));
        }
    }

    @Test
    void retentionFloorCanProtectEntireWal() {
        try (FileWal wal = new FileWal(tempDir, 64, 0)) {
            wal.append(sample(1000));
            wal.append(sample(2000));

            // Фолловер подключён, но ещё не подтвердил записи.
            wal.setWalRetentionFloor(
                    () -> Optional.of(new WalPosition(0, 0))
            );

            wal.truncateBefore(3000);

            assertTrue(Files.exists(
                    tempDir.resolve("00000001")));

            // Убираем ограничение.
            wal.setWalRetentionFloor(Optional::empty);
            wal.truncateBefore(3000);

            assertFalse(Files.exists(
                    tempDir.resolve("00000001")));
        }
    }


    @Test
    void lastPositionIsEmptyForNewWal() {
        try (FileWal wal = new FileWal(tempDir)) {
            assertTrue(wal.lastPosition().isEmpty());
        }
    }

    @Test
    void lastPositionIsRestoredAfterRestart() {
        WalPosition expected;

        // Первый запуск: записываем данные.
        try (FileWal wal = new FileWal(tempDir, 64, 0)) {
            wal.append(sample(1000));
            wal.append(sample(2000));

            expected = wal.append(sample(3000));

            assertEquals(
                    expected,
                    wal.lastPosition().orElseThrow()
            );
        }

        // Второй запуск: открываем тот же WAL.
        try (FileWal reopened = new FileWal(tempDir, 64, 0)) {

            assertEquals(
                    expected,
                    reopened.lastPosition().orElseThrow()
            );

            // Проверяем, что чтение тоже работает.
            try (var entries = reopened.readFrom(expected)) {
                assertEquals(1, entries.count());
            }
        }
    }


}
