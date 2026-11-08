package org.tsdb.head;

import java.util.List;
import java.util.Objects;

import org.tsdb.encoding.Chunk;
import org.tsdb.model.Series;

/** Серия и её закрытые чанки по возрастанию времени. */
public record SeriesChunks(Series series, List<Chunk> chunks) {
    public SeriesChunks {
        Objects.requireNonNull(series, "series");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }
}
