
package org.tsdb.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.wal.FileWal;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationRetentionTest {

    @TempDir
    Path tempDir;

    @Test
    void followerProgressControlsWalRetention() {

        ReplicationHub hub = new ReplicationHub(8);

        try (FileWal wal = new FileWal(tempDir, 64, 0)) {

            // Подключаем FileWal к ReplicationHub.
            wal.setAppendListener(hub::onAppend);
            wal.setWalRetentionFloor(hub::retentionFloor);

            // Лидер записывает первую точку.
            WalPosition first = wal.append(sample(1000));

            // Подключается фолловер.
            hub.connected("n2");
            hub.onAck("n2", first, 1000);

            // Лидер продолжает запись.
            WalPosition second = wal.append(sample(2000));
            WalPosition third = wal.append(sample(3000));

            // При размере сегмента 64 байта
            // каждая такая запись попадает в новый сегмент.
            assertTrue(second.segment() > first.segment());
            assertTrue(third.segment() > second.segment());

            // Пытаемся очистить старый WAL.
            wal.truncateBefore(2500);

            // Первый сегмент пока защищён фолловером.
            assertTrue(Files.exists(segment(first)));

            // Фолловер догнал лидера.
            hub.onAck("n2", third, 3000);

            // Снова очищаем WAL.
            wal.truncateBefore(2500);

            // Теперь первый сегмент можно удалить.
            assertFalse(Files.exists(segment(first)));

            // Последний сегмент остаётся.
            assertTrue(Files.exists(segment(third)));
        }
    }

    private Path segment(WalPosition position) {
        return tempDir.resolve(
                String.format("%08d", position.segment()));
    }

    private static WalRecord sample(long timestamp) {
        return new WalRecord.Samples(
                List.of(new SampleEntry(1, timestamp, 42.0)));
    }
}
