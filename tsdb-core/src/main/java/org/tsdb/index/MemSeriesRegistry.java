package org.tsdb.index;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

import org.tsdb.model.Labels;
import org.tsdb.model.Series;

// In-memory registry для head. Потокобезопасен: чтение без блокировок, выдача id — под монитором,
// чтобы две параллельные getOrCreate одной серии не получили разные id.
public final class MemSeriesRegistry implements SeriesRegistry {
    private final ConcurrentHashMap<Labels, Long> byLabels = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Long, Labels> byId = new ConcurrentSkipListMap<>();
    private long nextId = 1; // под this

    // Зарегистрировать серию с заданным id: replay WAL (SeriesCreated, checkpoint) и репликация.
    // Идемпотентный: повтор с теми же labels — no-op; id или labels уже заняты другой парой — IllegalStateException.
    // После restore следующий id из getOrCreate = max(id) + 1.
    public synchronized void restore(long id, Labels labels) {
        Objects.requireNonNull(labels, "labels");
        if (id <= 0) {
            throw new IllegalArgumentException("id must be > 0: " + id);
        }
        Labels existing = byId.get(id);
        Long existingId = byLabels.get(labels);
        if (existing != null || existingId != null) {
            if (labels.equals(existing) && existingId != null && existingId == id) {
                return;
            }
            throw new IllegalStateException("restore(" + id + ", " + labels + ") conflicts with "
                    + (existing != null ? id + " -> " + existing : labels + " -> " + existingId));
        }
        put(id, labels);
        nextId = Math.max(nextId, id + 1);
    }

    @Override
    public long getOrCreate(Labels labels) {
        Long id = byLabels.get(Objects.requireNonNull(labels, "labels"));
        if (id != null) {
            return id;
        }
        synchronized (this) {
            id = byLabels.get(labels);
            if (id != null) {
                return id;
            }
            long newId = nextId++;
            put(newId, labels);
            return newId;
        }
    }

    // Сначала byId, потом byLabels: кто увидел id через get(labels), тот найдёт и labels(id).
    private void put(long id, Labels labels) {
        byId.put(id, labels);
        byLabels.put(labels, id);
    }

    @Override
    public OptionalLong get(Labels labels) {
        Long id = byLabels.get(labels);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    @Override
    public Optional<Labels> labels(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public int size() {
        return byId.size();
    }

    // Серии по возрастанию id.
    @Override
    public Stream<Series> all() {
        return byId.entrySet().stream().map(e -> new Series(e.getKey(), e.getValue()));
    }
}
