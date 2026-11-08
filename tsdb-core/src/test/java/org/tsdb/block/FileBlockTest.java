package org.tsdb.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.encoding.*;
import org.tsdb.head.*;
import org.tsdb.model.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

class FileBlockTest {
    @TempDir Path temp;

    private HeadSnapshot snapshot(String host, long... timestamps) {
        AppendableChunk chunk = new GorillaCodec().newAppendable();
        for (long ts : timestamps) chunk.append(ts, ts / 100.0);
        SeriesChunks series = new SeriesChunks(new Series(99, Labels.of("cpu", "host", host)),
                timestamps.length == 0 ? List.of() : List.of(chunk.seal()));
        return new HeadSnapshot() {
            public long minTime() { return Arrays.stream(timestamps).min().orElse(Long.MAX_VALUE); }
            public long maxTime() { return Arrays.stream(timestamps).max().orElse(Long.MIN_VALUE); }
            public Stream<SeriesChunks> series() { return Stream.of(series); }
        };
    }

    @Test void writesReadsAndPreservesSamples() {
        Path dir = temp.resolve("block-a");
        BlockMeta meta = new FileBlockWriter(2).write(snapshot("node1", 100, 200, 300), dir);
        assertEquals(3, meta.numSamples());
        assertEquals(2, meta.numChunks());
        assertTrue(Files.exists(dir.resolve("meta.json")));
        assertTrue(Files.exists(dir.resolve("index")));
        assertTrue(Files.exists(dir.resolve("chunks.bin")));
        try (Block block = new FileBlockReader().open(dir)) {
            assertEquals(1, block.registry().size());
            assertEquals(Labels.of("cpu", "host", "node1"), block.labels(1).orElseThrow());
            List<Long> observed = new ArrayList<>();
            for (Chunk c : block.chunks(1, 0, 1000)) {
                SampleIterator iter = c.iterator();
                while (iter.next()) observed.add(iter.timestamp());
            }
            assertEquals(List.of(100L, 200L, 300L), observed);
            assertEquals(1, block.chunks(1, 250, 1000).size());
        }
    }

    @Test void mergesAndDeduplicatesByTimestamp() {
        FileBlockWriter writer = new FileBlockWriter();
        writer.write(snapshot("node1", 100, 200), temp.resolve("b1"));
        writer.write(snapshot("node1", 200, 300), temp.resolve("b2"));
        FileBlockReader reader = new FileBlockReader();
        try (Block one = reader.open(temp.resolve("b1")); Block two = reader.open(temp.resolve("b2"))) {
            BlockMeta merged = writer.merge(List.of(one, two), temp.resolve("b3"));
            assertEquals(3, merged.numSamples());
            assertEquals(1, merged.compactionLevel());
            assertEquals(List.of("b1", "b2"), merged.sources());
            try (Block result = reader.open(temp.resolve("b3"))) {
                List<Long> ts = new ArrayList<>();
                for (Chunk chunk : result.chunks(1, 0, 1000)) {
                    SampleIterator it = chunk.iterator();
                    while (it.next()) ts.add(it.timestamp());
                }
                assertEquals(List.of(100L, 200L, 300L), ts);
            }
        }
    }

    @Test void rejectsEmptySnapshot() {
        HeadSnapshot empty = new HeadSnapshot() {
            public long minTime() { return Long.MAX_VALUE; }
            public long maxTime() { return Long.MIN_VALUE; }
            public Stream<SeriesChunks> series() { return Stream.empty(); }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new FileBlockWriter().write(empty, temp.resolve("empty")));
    }

    @Test void detectsCorruption() throws Exception {
        Path dir = temp.resolve("broken");
        new FileBlockWriter().write(snapshot("node1", 100), dir);
        Path file = dir.resolve("chunks.bin");
        byte[] b = Files.readAllBytes(file);
        b[b.length - 1] ^= 1;
        Files.write(file, b);
        assertThrows(CorruptedBlockException.class, () -> new FileBlockReader().open(dir));
    }
}
