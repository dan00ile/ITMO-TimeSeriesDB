package org.tsdb.block;

import org.tsdb.encoding.*;
import org.tsdb.index.*;
import org.tsdb.model.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Opens and validates a published immutable block. */
public final class FileBlockReader implements BlockReader {
    @Override public Block open(Path dir) {
        Objects.requireNonNull(dir);
        try {
            BlockMeta meta = BlockFileFormat.readMeta(dir.resolve("meta.json"));
            List<BlockFileFormat.SeriesRef> series = BlockFileFormat.readIndex(dir.resolve("index"));
            byte[] bytes = Files.readAllBytes(dir.resolve("chunks.bin"));
            if (!meta.ulid().equals(dir.getFileName().toString()) || meta.numSeries() != series.size())
                throw new IOException("Block metadata mismatch");
            Map<Long, List<Chunk>> chunks = new TreeMap<>();
            Map<Long, Labels> labelsById = new TreeMap<>();
            long numChunks = 0, numSamples = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            String previous = null;
            for (BlockFileFormat.SeriesRef s : series) {
                String canonical = s.labels().canonical();
                if (previous != null && previous.compareTo(canonical) >= 0)
                    throw new IOException("Series order is invalid");
                previous = canonical;
                labelsById.put(s.id(), s.labels());
                List<Chunk> parts = new ArrayList<>();
                for (BlockFileFormat.ChunkRef ref : s.chunks()) {
                    if (ref.offset() > bytes.length || ref.length() > bytes.length - ref.offset())
                        throw new IOException("Chunk points outside chunks.bin");
                    byte[] slice = Arrays.copyOfRange(bytes, (int) ref.offset(), (int) (ref.offset() + ref.length()));
                    if (BlockFileFormat.crc(slice) != ref.crc()) throw new IOException("Chunk CRC mismatch");
                    Chunk chunk = new GorillaCodec().decode(slice);
                    if (chunk.numSamples() == 0 || chunk.minTime() != ref.minTime() || chunk.maxTime() != ref.maxTime())
                        throw new IOException("Chunk time bounds mismatch");
                    parts.add(chunk);
                    numChunks++;
                    numSamples += chunk.numSamples();
                    min = Math.min(min, chunk.minTime());
                    max = Math.max(max, chunk.maxTime());
                }
                chunks.put(s.id(), List.copyOf(parts));
            }
            if (numChunks != meta.numChunks() || numSamples != meta.numSamples()
                    || min != meta.minTime() || max != meta.maxTime())
                throw new IOException("Block aggregate counts mismatch");
            return new DiskBlock(meta, dir, labelsById, chunks);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw new CorruptedBlockException("Cannot read block: " + dir, e);
        }
    }

    private record DiskBlock(BlockMeta meta, Path dir, Map<Long, Labels> names,
                             Map<Long, List<Chunk>> stored) implements Block {
        @Override public SeriesRegistry registry() {
            return new SeriesRegistry() {
                @Override public long getOrCreate(Labels labels) { throw new UnsupportedOperationException("Disk block is immutable"); }
                @Override public OptionalLong get(Labels labels) {
                    return names.entrySet().stream().filter(e -> e.getValue().equals(labels))
                            .mapToLong(Map.Entry::getKey).findFirst();
                }
                @Override public Optional<Labels> labels(long id) { return Optional.ofNullable(names.get(id)); }
                @Override public int size() { return names.size(); }
                @Override public Stream<Series> all() {
                    return names.entrySet().stream().map(e -> new Series(e.getKey(), e.getValue()));
                }
            };
        }
        @Override public InvertedIndex index() {
            return new InvertedIndex() {
                @Override public void add(long id, Labels labels) { throw new UnsupportedOperationException("Disk block is immutable"); }
                @Override public Postings postings(String key, String value) {
                    return Postings.of(names.entrySet().stream()
                            .filter(e -> e.getValue().get(key).filter(value::equals).isPresent())
                            .mapToLong(Map.Entry::getKey).toArray());
                }
                @Override public Set<String> labelNames() {
                    return names.values().stream().flatMap(l -> l.labels().stream()).map(Label::name).collect(Collectors.toSet());
                }
                @Override public Set<String> labelValues(String key) {
                    return names.values().stream().flatMap(l -> l.labels().stream())
                            .filter(l -> l.name().equals(key)).map(Label::value).collect(Collectors.toSet());
                }
                @Override public Postings select(List<Matcher> matchers) {
                    long[] ids = names.entrySet().stream().filter(e -> matchers.stream().allMatch(m -> matches(e.getValue(), m)))
                            .mapToLong(Map.Entry::getKey).toArray();
                    return Postings.of(ids);
                }
            };
        }
        private static boolean matches(Labels labels, Matcher matcher) {
            Optional<String> v = labels.get(matcher.name());
            if (matcher instanceof Matcher.Eq m) return v.filter(m.value()::equals).isPresent();
            if (matcher instanceof Matcher.NotEq m) return v.isEmpty() || !v.get().equals(m.value());
            if (matcher instanceof Matcher.Regex m) return v.isPresent() && v.get().matches(m.pattern());
            if (matcher instanceof Matcher.NotRegex m) return v.isEmpty() || !v.get().matches(m.pattern());
            throw new IllegalArgumentException("Unknown matcher");
        }
        @Override public List<Chunk> chunks(long id, long from, long to) {
            if (from > to) return List.of();
            return stored.getOrDefault(id, List.of()).stream()
                    .filter(c -> c.maxTime() >= from && c.minTime() <= to).toList();
        }
        @Override public void close() { /* fully materialized; no open handles */ }
    }
}
