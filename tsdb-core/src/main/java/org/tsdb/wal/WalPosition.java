package org.tsdb.wal;

/**
 * Позиция записи внутри WAL.
 *
 * Нужна в том числе для будущей репликации.
 */
public record WalPosition(
        long segment,
        long offset
) implements Comparable<WalPosition> {

    @Override
    public int compareTo(WalPosition other) {
        int segmentCompare =
                Long.compare(segment, other.segment);

        if (segmentCompare != 0) {
            return segmentCompare;
        }

        return Long.compare(offset, other.offset);
    }
}