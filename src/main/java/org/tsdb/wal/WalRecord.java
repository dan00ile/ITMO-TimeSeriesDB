package org.tsdb.wal;

import org.tsdb.model.Labels;

import java.util.List;
import java.util.Objects;

/**
 * Тип записи в Write-Ahead Log.
 */
public sealed interface WalRecord
        permits WalRecord.SeriesCreated, WalRecord.Samples {

    /**
     * Запись о создании новой серии.
     */
    record SeriesCreated(
            long id,
            Labels labels
    ) implements WalRecord {

        public SeriesCreated {
            Objects.requireNonNull(labels, "labels");
        }
    }

    /**
     * Пакет точек.
     */
    record Samples(
            List<SampleEntry> entries
    ) implements WalRecord {

        public Samples {
            entries = List.copyOf(
                    Objects.requireNonNull(entries, "entries")
            );
        }
    }
}