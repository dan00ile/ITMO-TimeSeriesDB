package org.tsdb.head;

import java.util.stream.Stream;

public interface HeadSnapshot {
    long minTime();

    long maxTime();

    Stream<SeriesChunks> series();
}
