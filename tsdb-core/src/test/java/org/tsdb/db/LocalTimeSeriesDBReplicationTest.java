
package org.tsdb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;
import org.tsdb.wal.FileWal;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalTimeSeriesDBReplicationTest {

    @TempDir
    Path tempDir;

    private DbOptions options() {
        return DbOptions.builder(tempDir)
                .walSyncIntervalMs(0)
                .compactionIntervalMs(0)
                .build();
    }

    private Labels labels() {
        return new Labels(List.of(
                new Label(Labels.METRIC_NAME, "cpu_usage"),
                new Label("host", "server1")
        ));
    }

    @Test
    void appendBatchAtReturnsWalPosition() {
        DbOptions options = options();
        WalPosition position;

        try (LocalTimeSeriesDB db =
                     LocalTimeSeriesDB.open(options)) {

            position = db.appendBatchAt(List.of(
                    new SampleInput(labels(), 1000, 42.0)
            ));

            assertNotNull(position);
            assertTrue(position.segment() > 0);
        }

        // Проверяем, что возвращённая позиция
        // действительно указывает на запись Samples.
        try (FileWal wal = new FileWal(options);
             var entries = wal.readFrom(position)) {

            var records = entries.toList();

            assertEquals(1, records.size());

            assertInstanceOf(
                    WalRecord.Samples.class,
                    records.get(0).record()
            );

            WalRecord.Samples samples =
                    (WalRecord.Samples) records.get(0).record();

            assertEquals(1, samples.entries().size());
            assertEquals(1000,
                    samples.entries().get(0).timestamp());
            assertEquals(42.0,
                    samples.entries().get(0).value());
        }
    }

    @Test
    void emptyBatchIsRejected() {
        try (LocalTimeSeriesDB db =
                     LocalTimeSeriesDB.open(options())) {

            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.appendBatchAt(List.of())
            );
        }
    }

    @Test
    void oldAppendBatchStillWorks() {
        try (LocalTimeSeriesDB db =
                     LocalTimeSeriesDB.open(options())) {

            assertDoesNotThrow(() ->
                    db.appendBatch(List.of(
                            new SampleInput(labels(), 1000, 42.0)
                    ))
            );
        }
    }
}
