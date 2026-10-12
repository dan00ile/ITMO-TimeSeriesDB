
package org.tsdb.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.model.Labels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileWalTest {

    @TempDir
    Path tempDir;

    @Test
    void appendReplayAndReopen() {
        WalRecord.SeriesCreated created =
                new WalRecord.SeriesCreated(
                        42L,
                        Labels.of("cpu_usage", "host", "server-1"));

        WalRecord.Samples samples =
                new WalRecord.Samples(List.of(
                        new SampleEntry(42L, 1000L, 0.5),
                        new SampleEntry(42L, 2000L, 0.6)));

        try (FileWal wal = new FileWal(tempDir)) {
            wal.append(created);
            wal.append(samples);
            wal.sync();
        }

        try (FileWal wal = new FileWal(tempDir)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);

            assertEquals(List.of(created, samples), restored);
        }
    }

    @Test
    void readFromPosition() {
        try (FileWal wal = new FileWal(tempDir)) {
            WalRecord first = new WalRecord.Samples(
                    List.of(new SampleEntry(1, 100, 1.0)));

            WalRecord second = new WalRecord.Samples(
                    List.of(new SampleEntry(1, 200, 2.0)));

            wal.append(first);
            WalPosition secondPosition = wal.append(second);

            try (var entries = wal.readFrom(secondPosition)) {
                List<WalEntry> result = entries.toList();

                assertEquals(1, result.size());
                assertEquals(secondPosition, result.get(0).position());
                assertEquals(second, result.get(0).record());
            }

            assertThrows(IllegalArgumentException.class,
                    () -> wal.readFrom(
                            new WalPosition(
                                    secondPosition.segment(),
                                    secondPosition.offset() + 1)));
        }
    }

    @Test
    void rotatesSegments() {
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            WalPosition first = wal.append(
                    new WalRecord.Samples(
                            List.of(new SampleEntry(1, 100, 1.0))));

            WalPosition second = wal.append(
                    new WalRecord.Samples(
                            List.of(new SampleEntry(1, 200, 2.0))));

            assertTrue(second.segment() > first.segment());
        }
    }

    @Test
    void detectsCorruptedPayload() throws Exception {
        try (FileWal wal = new FileWal(tempDir)) {
            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 100, 1.0))));
        }

        Path segment = tempDir.resolve("00000001");
        byte[] bytes = Files.readAllBytes(segment);

        bytes[bytes.length - 1] ^= 1;
        Files.write(segment, bytes);

        assertThrows(CorruptedWalException.class,
                () -> new FileWal(tempDir));
    }

    @Test
    void repairsIncompleteLastRecord() throws Exception {
        try (FileWal wal = new FileWal(tempDir)) {
            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 100, 1.0))));
        }

        Path segment = tempDir.resolve("00000001");

        Files.write(segment, new byte[]{0, 0, 0},
                java.nio.file.StandardOpenOption.APPEND);

        try (FileWal wal = new FileWal(tempDir)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);

            assertEquals(1, restored.size());
        }
    }

    @Test
    void truncateRemovesOnlyOldCompletedSegments() throws Exception {
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 100, 1.0))));

            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 200, 2.0))));

            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 300, 3.0))));

            wal.truncateBefore(250);

            assertFalse(Files.exists(tempDir.resolve("00000001")));
            assertFalse(Files.exists(tempDir.resolve("00000002")));
            assertTrue(Files.exists(tempDir.resolve("00000003")));
        }
    }

    // Сегмент с SeriesCreated удаляется, серия переживает рестарт через checkpoint.
    @Test
    void seriesSurviveTruncateViaCheckpoint() {
        Labels cpu = Labels.of("cpu_usage", "host", "web-1");
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            wal.append(new WalRecord.SeriesCreated(7, cpu));                          // сегмент 1
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(7, 100, 1.0)))); // сегмент 2
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(7, 300, 3.0)))); // сегмент 3

            wal.truncateBefore(250);

            assertFalse(Files.exists(tempDir.resolve("00000001")));
            assertFalse(Files.exists(tempDir.resolve("00000002")));
            assertTrue(Files.exists(tempDir.resolve("checkpoint.00000003")));
        }

        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);
            assertEquals(List.of(
                    new WalRecord.SeriesCreated(7, cpu),
                    new WalRecord.Samples(List.of(new SampleEntry(7, 300, 3.0)))), restored);
        }
    }

    // Новый checkpoint содержит и серии из прошлого checkpoint'а, старый удаляется.
    @Test
    void checkpointCarriesSeriesAcrossRestarts() {
        Labels a = Labels.of("cpu_usage", "host", "a");
        Labels b = Labels.of("cpu_usage", "host", "b");
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            wal.append(new WalRecord.SeriesCreated(1, a));
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(1, 100, 1.0))));
            wal.truncateBefore(150);
        }
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            wal.append(new WalRecord.SeriesCreated(2, b));
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(2, 200, 2.0))));
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(1, 300, 3.0))));
            wal.truncateBefore(250);
        }
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);
            assertEquals(List.of(
                    new WalRecord.SeriesCreated(1, a),
                    new WalRecord.SeriesCreated(2, b),
                    new WalRecord.Samples(List.of(new SampleEntry(1, 300, 3.0)))), restored);
        }
        try (var files = assertDoesNotThrow(() -> Files.list(tempDir))) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("checkpoint.")).count());
        }
    }

    // После flush всего head устарел и активный сегмент: WAL переходит на новый и удаляет старый.
    @Test
    void fullyExpiredActiveSegmentIsRotatedAndDeleted() {
        Labels cpu = Labels.of("cpu_usage", "host", "web-1");
        try (FileWal wal = new FileWal(tempDir)) {
            wal.append(new WalRecord.SeriesCreated(1, cpu));
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(1, 100, 1.0))));

            wal.truncateBefore(101);

            assertFalse(Files.exists(tempDir.resolve("00000001")));
            wal.append(new WalRecord.Samples(List.of(new SampleEntry(1, 200, 2.0))));
        }
        try (FileWal wal = new FileWal(tempDir)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);
            assertEquals(List.of(
                    new WalRecord.SeriesCreated(1, cpu),
                    new WalRecord.Samples(List.of(new SampleEntry(1, 200, 2.0)))), restored);
        }
    }

    @Test
    void leftoverCheckpointTmpIgnored() throws Exception {
        Files.write(tempDir.resolve("checkpoint.tmp"), new byte[] {1, 2, 3});
        try (FileWal wal = new FileWal(tempDir)) {
            List<WalRecord> restored = new ArrayList<>();
            wal.replay(restored::add);
            assertTrue(restored.isEmpty());
        }
        assertFalse(Files.exists(tempDir.resolve("checkpoint.tmp")));
    }

    @Test
    void readFromExpiredPositionRequiresResync() {
        try (FileWal wal = new FileWal(tempDir, 60, 0)) {
            WalPosition oldPosition = wal.append(
                    new WalRecord.Samples(
                            List.of(new SampleEntry(1, 100, 1.0))));

            wal.append(new WalRecord.Samples(
                    List.of(new SampleEntry(1, 200, 2.0))));

            wal.truncateBefore(150);

            assertThrows(IllegalStateException.class,
                    () -> wal.readFrom(oldPosition));
        }
    }
}
