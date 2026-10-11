package org.tsdb.head;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import org.tsdb.encoding.AppendableChunk;
import org.tsdb.encoding.Chunk;
import org.tsdb.encoding.ChunkCodec;
import org.tsdb.index.MemInvertedIndex;
import org.tsdb.index.MemSeriesRegistry;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Series;

// In-memory head. Lock на серию: разные серии пишутся параллельно и не делят chunk'и.
// truncate берёт write-lock на весь head, append — read-lock: пересчёт minTime/maxTime
// после truncate не теряет параллельные обновления. Размер chunk'а задаёт codec (isFull).
public final class MemHead implements Head {
    private final ChunkCodec codec;
    private final MemSeriesRegistry registry = new MemSeriesRegistry();
    private final MemInvertedIndex index = new MemInvertedIndex();
    private final ConcurrentHashMap<Long, MemSeries> series = new ConcurrentHashMap<>();
    private final ReadWriteLock truncateLock = new ReentrantReadWriteLock();
    private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE); // пустой head: min > max
    private final AtomicLong maxTime = new AtomicLong(Long.MIN_VALUE);

    public MemHead(ChunkCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    private static final class MemSeries {
        final long id;
        final ReentrantLock lock = new ReentrantLock();
        AppendableChunk open;                         // под lock
        final List<Chunk> sealed = new ArrayList<>(); // закрытые chunk'и по возрастанию времени, под lock
        long lastTs = Long.MIN_VALUE;                 // под lock

        MemSeries(long id, AppendableChunk open) {
            this.id = id;
            this.open = open;
        }
    }

    @Override
    public long minTime() {
        return minTime.get();
    }

    @Override
    public long maxTime() {
        return maxTime.get();
    }

    // Конкретный тип: фасаду нужен restore() при replay WAL.
    @Override
    public MemSeriesRegistry registry() {
        return registry;
    }

    @Override
    public MemInvertedIndex index() {
        return index;
    }

    // IllegalArgumentException, если серии нет в registry; OutOfOrderSampleException, если timestamp <= lastTs.
    @Override
    public void append(long seriesId, long timestamp, double value) {
        MemSeries s = seriesFor(seriesId);
        truncateLock.readLock().lock();
        try {
            s.lock.lock();
            try {
                if (timestamp <= s.lastTs) {
                    throw new OutOfOrderSampleException(seriesId, timestamp, s.lastTs);
                }
                s.open.append(timestamp, value);
                s.lastTs = timestamp;
                if (s.open.isFull()) {
                    s.sealed.add(s.open.seal());
                    s.open = codec.newAppendable();
                }
            } finally {
                s.lock.unlock();
            }
            minTime.accumulateAndGet(timestamp, Math::min);
            maxTime.accumulateAndGet(timestamp, Math::max);
        } finally {
            truncateLock.readLock().unlock();
        }
    }

    private MemSeries seriesFor(long seriesId) {
        MemSeries s = series.get(seriesId);
        if (s != null) {
            return s;
        }
        if (registry.labels(seriesId).isEmpty()) {
            throw new IllegalArgumentException("unknown series id " + seriesId);
        }
        return series.computeIfAbsent(seriesId, id -> new MemSeries(id, codec.newAppendable()));
    }

    // Открытый chunk отдаётся копией (decode его байт): писатель продолжает дописывать в оригинал.
    @Override
    public List<Chunk> chunks(long seriesId, long start, long end) {
        MemSeries s = series.get(seriesId);
        if (s == null) {
            return List.of();
        }
        List<Chunk> out = new ArrayList<>();
        s.lock.lock();
        try {
            for (Chunk c : s.sealed) {
                if (overlaps(c, start, end)) {
                    out.add(c);
                }
            }
            if (s.open.numSamples() > 0 && overlaps(s.open, start, end)) {
                out.add(codec.decode(s.open.bytes()));
            }
        } finally {
            s.lock.unlock();
        }
        return List.copyOf(out);
    }

    private static boolean overlaps(Chunk c, long start, long end) {
        return c.maxTime() >= start && c.minTime() <= end;
    }

    // Забирает chunk'и с maxTime < upTo. Открытый chunk целиком до границы закрывается и тоже уходит:
    // иначе серия, переставшая писать, держала бы данные в head, а WAL под ними уже удалён.
    // Открытый chunk, пересекающий границу, остаётся в head и уйдёт в следующий блок.
    @Override
    public HeadSnapshot truncate(long upTo) {
        truncateLock.writeLock().lock();
        try {
            List<SeriesChunks> out = new ArrayList<>();
            long snapMin = Long.MAX_VALUE;
            long snapMax = Long.MIN_VALUE;
            long headMin = Long.MAX_VALUE;
            long headMax = Long.MIN_VALUE;
            List<MemSeries> ordered = new ArrayList<>(series.values());
            ordered.sort(Comparator.comparingLong(s -> s.id));
            for (MemSeries s : ordered) {
                s.lock.lock();
                try {
                    if (s.open.numSamples() > 0 && s.open.maxTime() < upTo) {
                        s.sealed.add(s.open.seal());
                        s.open = codec.newAppendable();
                    }
                    List<Chunk> taken = new ArrayList<>();
                    for (Iterator<Chunk> it = s.sealed.iterator(); it.hasNext(); ) {
                        Chunk c = it.next();
                        if (c.maxTime() < upTo) {
                            taken.add(c);
                            it.remove();
                            snapMin = Math.min(snapMin, c.minTime());
                            snapMax = Math.max(snapMax, c.maxTime());
                        } else {
                            headMin = Math.min(headMin, c.minTime());
                            headMax = Math.max(headMax, c.maxTime());
                        }
                    }
                    if (s.open.numSamples() > 0) {
                        headMin = Math.min(headMin, s.open.minTime());
                        headMax = Math.max(headMax, s.open.maxTime());
                    }
                    if (!taken.isEmpty()) {
                        out.add(new SeriesChunks(new Series(s.id, registry.labels(s.id).orElseThrow()), taken));
                    }
                } finally {
                    s.lock.unlock();
                }
            }
            minTime.set(headMin);
            maxTime.set(headMax);
            return new Snapshot(snapMin, snapMax, List.copyOf(out));
        } finally {
            truncateLock.writeLock().unlock();
        }
    }

    // Пустой снимок: minTime == Long.MAX_VALUE, maxTime == Long.MIN_VALUE.
    private record Snapshot(long minTime, long maxTime, List<SeriesChunks> list) implements HeadSnapshot {
        @Override
        public Stream<SeriesChunks> series() {
            return list.stream();
        }
    }
}
