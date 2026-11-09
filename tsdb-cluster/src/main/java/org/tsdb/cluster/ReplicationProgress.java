package org.tsdb.cluster;

import org.tsdb.wal.WalPosition;

/** Позиция WAL лидера, а не локального WAL. null означает, что ничего ещё не применено. */
public record ReplicationProgress(WalPosition applied, long appliedMaxTs) {
    public static ReplicationProgress empty() {
        return new ReplicationProgress(null, Long.MIN_VALUE);
    }
}
