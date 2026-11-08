package org.tsdb.db;

import java.util.Objects;

import org.tsdb.model.Labels;

// Точка на входе БД: снаружи seriesId не знают, поэтому лейблы. Единица пакетной записи.
public record SampleInput(Labels labels, long timestamp, double value) {
    public SampleInput {
        Objects.requireNonNull(labels, "labels");
    }
}
