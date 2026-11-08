package org.tsdb.wal;

import java.util.Objects;

/**
 * WAL-запись вместе с её позицией.
 */
public record WalEntry(
        WalPosition position,
        WalRecord record
) {

    public WalEntry {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(record, "record");
    }
}