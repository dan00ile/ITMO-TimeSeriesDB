package org.tsdb.model;

import java.util.Objects;

// Серия внутри одного источника (head или блок).
public record Series(long id, Labels labels) {
    public Series {
        Objects.requireNonNull(labels, "labels");
    }
}
