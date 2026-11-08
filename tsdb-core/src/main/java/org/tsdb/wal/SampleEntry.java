package org.tsdb.wal;

/**
 * Одна точка временного ряда внутри WAL.
 */
public record SampleEntry(
        long seriesId,
        long timestamp,
        double value
) {
}