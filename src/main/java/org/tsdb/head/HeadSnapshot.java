package org.tsdb.head;

import java.util.stream.Stream;

// Пустой снимок: minTime() == Long.MAX_VALUE, maxTime() == Long.MIN_VALUE, series() пуст.
// BlockWriter.write на пустом снимке — IllegalArgumentException; фасад такой снимок не пишет.
public interface HeadSnapshot {
    long minTime();

    long maxTime();

    Stream<SeriesChunks> series();
}
