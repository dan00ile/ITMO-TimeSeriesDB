package org.tsdb.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.tsdb.block.Block;
import org.tsdb.block.BlockMeta;
import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.block.CorruptedBlockException;
import org.tsdb.encoding.Chunk;
import org.tsdb.head.HeadSnapshot;
import org.tsdb.head.SeriesChunks;
import org.tsdb.index.InvertedIndex;
import org.tsdb.index.SeriesRegistry;
import org.tsdb.model.Labels;
import org.tsdb.model.Sample;
import org.tsdb.model.Series;

// Блоки: на диске только каталог с meta.json, сами точки — в статической мапе по ulid.
// ulid блока — имя каталога, в который его попросили записать.
public final class FakeBlocks implements BlockWriter, BlockReader {

    private static final Map<String, FakeBlock> DISK = new ConcurrentHashMap<>();

    @Override
    public BlockMeta write(HeadSnapshot snapshot, Path targetDir) {
        Map<Labels, List<Sample>> series = new TreeMap<>(Comparator.comparing(Labels::canonical));
        for (SeriesChunks s : snapshot.series().toList()) {
            List<Sample> samples = series.computeIfAbsent(s.series().labels(), l -> new ArrayList<>());
            for (Chunk chunk : s.chunks()) {
                samples.addAll(ListChunk.samplesOf(chunk));
            }
        }
        if (series.isEmpty()) {
            throw new IllegalArgumentException("empty snapshot");
        }
        return save(targetDir, series, 1, List.of());
    }

    @Override
    public BlockMeta merge(List<Block> blocks, Path targetDir) {
        Map<Labels, List<Sample>> series = new TreeMap<>(Comparator.comparing(Labels::canonical));
        List<String> sources = new ArrayList<>();
        int level = 0;
        for (Block block : blocks) {
            sources.add(block.meta().ulid());
            level = Math.max(level, block.meta().compactionLevel());
            for (Series s : block.registry().all().toList()) {
                List<Sample> samples = series.computeIfAbsent(s.labels(), l -> new ArrayList<>());
                for (Chunk chunk : block.chunks(s.id(), Long.MIN_VALUE, Long.MAX_VALUE)) {
                    samples.addAll(ListChunk.samplesOf(chunk));
                }
            }
        }
        for (List<Sample> samples : series.values()) {
            samples.sort(Comparator.comparingLong(Sample::timestamp));
        }
        return save(targetDir, series, level + 1, sources);
    }

    @Override
    public Block open(Path dir) {
        FakeBlock stored = DISK.get(dir.getFileName().toString());
        if (stored == null || !Files.isDirectory(dir)) {
            throw new CorruptedBlockException("no block in " + dir);
        }
        return new FakeBlock(stored.meta(), dir, stored.data());
    }

    private static BlockMeta save(Path dir, Map<Labels, List<Sample>> series, int level, List<String> sources) {
        FakeHead data = new FakeHead();
        long numSamples = 0;
        for (Map.Entry<Labels, List<Sample>> e : series.entrySet()) {
            long id = data.registry().getOrCreate(e.getKey());
            data.index().add(id, e.getKey());
            for (Sample s : e.getValue()) {
                data.append(id, s.timestamp(), s.value());
            }
            numSamples += e.getValue().size();
        }
        String ulid = dir.getFileName().toString();
        BlockMeta meta = new BlockMeta(ulid, data.minTime(), data.maxTime(),
                series.size(), numSamples, series.size(), level, sources);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("meta.json"), meta.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DISK.put(ulid, new FakeBlock(meta, dir, data));
        return meta;
    }

    private record FakeBlock(BlockMeta meta, Path dir, FakeHead data) implements Block {
        @Override
        public SeriesRegistry registry() {
            return data.registry();
        }

        @Override
        public InvertedIndex index() {
            return data.index();
        }

        @Override
        public List<Chunk> chunks(long seriesId, long start, long end) {
            return data.chunks(seriesId, start, end);
        }

        @Override
        public void close() {
        }
    }
}
