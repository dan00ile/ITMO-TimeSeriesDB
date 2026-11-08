package org.tsdb.db;

// Снимок состояния БД: серии, блоки на диске, точки в head, размер dataDir.
// Значения согласованы только на момент вызова.
public record DbStats(long numSeries, long numBlocks, long headSamples, long diskBytes) {
    public DbStats {
        requireNonNegative("numSeries", numSeries);
        requireNonNegative("numBlocks", numBlocks);
        requireNonNegative("headSamples", headSamples);
        requireNonNegative("diskBytes", diskBytes);
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }
}
