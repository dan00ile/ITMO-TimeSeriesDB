package org.tsdb.model;

import java.io.Serial;

// Точка с ts <= lastTs серии. Out-of-order ingest не поддерживаем.
public class OutOfOrderSampleException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public OutOfOrderSampleException(long seriesId, long ts, long lastTs) {
        super("series " + seriesId + ": ts " + ts + " <= last " + lastTs);
    }
}
