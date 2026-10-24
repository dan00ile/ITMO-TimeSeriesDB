package org.tsdb.block;

import org.tsdb.encoding.*;
import org.tsdb.head.*;
import org.tsdb.model.Labels;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/** Writes immutable disk blocks using a temporary directory and atomic rename. */
public final class FileBlockWriter implements BlockWriter {
    private final int maxSamples;

    public FileBlockWriter() { this(120); }
    public FileBlockWriter(int maxSamples) {
        if (maxSamples < 1 || maxSamples > 65535) throw new IllegalArgumentException("Invalid chunk capacity");
        this.maxSamples = maxSamples;
    }

    @Override public BlockMeta write(HeadSnapshot snapshot, Path targetDir) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(targetDir);
        List<SeriesChunks> series;
        try (var stream = snapshot.series()) { series = stream.toList(); }
        if (series.isEmpty()) throw new IllegalArgumentException("Empty head snapshot");
        Map<String, SeriesData> collected = new TreeMap<>();
        for (SeriesChunks entry : series) {
            SeriesData sd = collected.computeIfAbsent(entry.series().labels().canonical(),
                    x -> new SeriesData(entry.series().labels()));
            for (Chunk chunk : entry.chunks()) collect(sd, chunk);
        }
        return writeSeries(collected.values(), targetDir, 0, List.of());
    }

    @Override public BlockMeta merge(List<Block> blocks, Path targetDir) {
        Objects.requireNonNull(blocks);
        Objects.requireNonNull(targetDir);
        if (blocks.isEmpty()) throw new IllegalArgumentException("No blocks to merge");
        Map<String, SeriesData> collected = new TreeMap<>();
        List<Block> ordered = blocks.stream().sorted(Comparator.comparing(b -> b.meta().ulid())).toList();
        List<String> sources = new ArrayList<>();
        int level = 0;
        for (Block block : ordered) {
            sources.add(block.meta().ulid());
            level = Math.max(level, block.meta().compactionLevel() + 1);
            try (var all = block.registry().all()) {
                for (var series : all.toList()) {
                    SeriesData sd = collected.computeIfAbsent(series.labels().canonical(),
                            x -> new SeriesData(series.labels()));
                    for (Chunk chunk : block.chunks(series.id(), Long.MIN_VALUE, Long.MAX_VALUE)) collect(sd, chunk);
                }
            }
        }
        return writeSeries(collected.values(), targetDir, level, sources);
    }

    private static void collect(SeriesData target, Chunk chunk) {
        SampleIterator it = chunk.iterator();
        while (it.next()) {
            // Stable deduplication: earlier source wins when timestamps overlap.
            target.points.putIfAbsent(it.timestamp(), it.value());
        }
    }

    private static final class SeriesData {
        final Labels labels;
        final NavigableMap<Long, Double> points = new TreeMap<>();
        SeriesData(Labels labels) { this.labels = labels; }
    }

    private BlockMeta writeSeries(Collection<SeriesData> series, Path targetDir,
                                  int level, List<String> sources) {
        Path absolute = targetDir.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        String id = absolute.getFileName().toString();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            if (Files.exists(absolute)) throw new FileAlreadyExistsException(absolute.toString());
            temp = Files.createTempDirectory(parent, ".block-pending-");
            List<BlockFileFormat.SeriesRef> refs = new ArrayList<>();
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE, count = 0, chunkCount = 0;
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp.resolve("chunks.bin")))) {
                long offset = 0;
                long seriesId = 0;
                for (SeriesData sd : series) {
                    List<BlockFileFormat.ChunkRef> chunks = new ArrayList<>();
                    AppendableChunk pending = new GorillaCodec(maxSamples).newAppendable();
                    for (Map.Entry<Long, Double> p : sd.points.entrySet()) {
                        if (pending.isFull()) {
                            byte[] bytes = pending.seal().bytes();
                            chunks.add(writeChunk(output, bytes, offset, pending.minTime(), pending.maxTime()));
                            offset += bytes.length;
                            chunkCount++;
                            pending = new GorillaCodec(maxSamples).newAppendable();
                        }
                        pending.append(p.getKey(), p.getValue());
                        min = Math.min(min, p.getKey());
                        max = Math.max(max, p.getKey());
                        count++;
                    }
                    if (pending.numSamples() > 0) {
                        byte[] bytes = pending.seal().bytes();
                        chunks.add(writeChunk(output, bytes, offset, pending.minTime(), pending.maxTime()));
                        offset += bytes.length;
                        chunkCount++;
                    }
                    refs.add(new BlockFileFormat.SeriesRef(++seriesId, sd.labels, chunks));
                }
            }
            BlockMeta meta = new BlockMeta(id, min, max, refs.size(), count, chunkCount, level, sources);
            BlockFileFormat.writeIndex(temp.resolve("index"), refs);
            BlockFileFormat.writeMeta(temp.resolve("meta.json"), meta);
            // Publish only after all three files are complete.
            Files.move(temp, absolute, ATOMIC_MOVE);
            return meta;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write block", e);
        } finally {
            if (temp != null && Files.exists(temp)) {
                try (var paths = Files.walk(temp)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }

    private static BlockFileFormat.ChunkRef writeChunk(OutputStream out, byte[] bytes,
                                                        long offset, long min, long max) throws IOException {
        if (bytes.length > BlockFileFormat.MAX_CHUNK) throw new IOException("Chunk too large");
        out.write(bytes);
        return new BlockFileFormat.ChunkRef(offset, bytes.length, BlockFileFormat.crc(bytes), min, max);
    }
}
