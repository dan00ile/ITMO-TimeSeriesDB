package org.tsdb.testing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.tsdb.encoding.Chunk;
import org.tsdb.head.Head;
import org.tsdb.head.HeadSnapshot;
import org.tsdb.head.SeriesChunks;
import org.tsdb.index.InvertedIndex;
import org.tsdb.index.SeriesRegistry;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Sample;
import org.tsdb.model.Series;

// Head без chunk'ов: у каждой серии просто список точек. Им же FakeBlocks хранит содержимое блока.
public final class FakeHead implements Head {

    private final FakeIndex index = new FakeIndex();
    private final Map<Long, List<Sample>> samples = new HashMap<>();

    @Override
    public SeriesRegistry registry() {
        return index;
    }

    @Override
    public InvertedIndex index() {
        return index;
    }

    @Override
    public synchronized long minTime() {
        long min = Long.MAX_VALUE;
        for (List<Sample> list : samples.values()) {
            if (!list.isEmpty()) {
                min = Math.min(min, list.get(0).timestamp());
            }
        }
        return min;
    }

    @Override
    public synchronized long maxTime() {
        long max = Long.MIN_VALUE;
        for (List<Sample> list : samples.values()) {
            if (!list.isEmpty()) {
                max = Math.max(max, list.get(list.size() - 1).timestamp());
            }
        }
        return max;
    }

    @Override
    public synchronized void append(long seriesId, long timestamp, double value) {
        if (index.labels(seriesId).isEmpty()) {
            throw new IllegalArgumentException("unknown series " + seriesId);
        }
        List<Sample> list = samples.computeIfAbsent(seriesId, id -> new ArrayList<>());
        if (!list.isEmpty() && timestamp <= list.get(list.size() - 1).timestamp()) {
            throw new OutOfOrderSampleException(seriesId, timestamp, list.get(list.size() - 1).timestamp());
        }
        list.add(new Sample(timestamp, value));
    }

    @Override
    public synchronized List<Chunk> chunks(long seriesId, long start, long end) {
        List<Sample> inRange = new ArrayList<>();
        for (Sample s : samples.getOrDefault(seriesId, List.of())) {
            if (s.timestamp() >= start && s.timestamp() <= end) {
                inRange.add(s);
            }
        }
        return inRange.isEmpty() ? List.of() : List.of(new ListChunk(inRange));
    }

    @Override
    public synchronized HeadSnapshot truncate(long upTo) {
        List<SeriesChunks> cut = new ArrayList<>();
        for (Series series : index.all().toList()) {
            List<Sample> old = new ArrayList<>();
            List<Sample> rest = new ArrayList<>();
            for (Sample s : samples.getOrDefault(series.id(), List.of())) {
                if (s.timestamp() < upTo) {
                    old.add(s);
                } else {
                    rest.add(s);
                }
            }
            if (!old.isEmpty()) {
                cut.add(new SeriesChunks(series, List.of(new ListChunk(old))));
                samples.put(series.id(), rest);
            }
        }
        return new Snapshot(cut);
    }

    private record Snapshot(List<SeriesChunks> cut) implements HeadSnapshot {
        @Override
        public long minTime() {
            long min = Long.MAX_VALUE;
            for (SeriesChunks s : cut) {
                min = Math.min(min, s.chunks().get(0).minTime());
            }
            return min;
        }

        @Override
        public long maxTime() {
            long max = Long.MIN_VALUE;
            for (SeriesChunks s : cut) {
                max = Math.max(max, s.chunks().get(s.chunks().size() - 1).maxTime());
            }
            return max;
        }

        @Override
        public Stream<SeriesChunks> series() {
            return cut.stream();
        }
    }
}
