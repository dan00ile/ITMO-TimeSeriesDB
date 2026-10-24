package org.tsdb.head;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tsdb.testing.ListChunk.samplesOf;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.tsdb.encoding.Chunk;
import org.tsdb.index.PostingsIterator;
import org.tsdb.model.Labels;
import org.tsdb.model.Matcher;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Sample;
import org.tsdb.testing.ListChunkCodec;

class HeadTest {
    private static final Labels WEB1 = Labels.of("cpu_usage", "host", "web-1");

    private static MemHead head(int chunkSize) {
        return new MemHead(new ListChunkCodec(chunkSize));
    }

    // Регистрация серии — как в фасаде: registry + index.
    private static long register(MemHead head, Labels labels) {
        long id = head.registry().getOrCreate(labels);
        head.index().add(id, labels);
        return id;
    }

    @Test
    void appendRollsOverChunks() {
        MemHead head = head(120);
        long id = register(head, WEB1);
        for (int i = 0; i < 200; i++) {
            head.append(id, i * 1_000L, i);
        }
        List<Chunk> chunks = head.chunks(id, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2, chunks.size());
        assertEquals(120, chunks.get(0).numSamples());
        assertEquals(80, chunks.get(1).numSamples());
        assertEquals(0, head.minTime());
        assertEquals(199_000, head.maxTime());
    }

    @Test
    void emptyHeadBounds() {
        MemHead head = head(4);
        assertEquals(Long.MAX_VALUE, head.minTime());
        assertEquals(Long.MIN_VALUE, head.maxTime());
    }

    @Test
    void outOfOrderRejected() {
        MemHead head = head(4);
        long id = register(head, WEB1);
        head.append(id, 1_000, 1);
        assertThrows(OutOfOrderSampleException.class, () -> head.append(id, 1_000, 2));
        assertThrows(OutOfOrderSampleException.class, () -> head.append(id, 500, 2));
    }

    @Test
    void outOfOrderDetectedAcrossChunkBoundary() {
        MemHead head = head(2);
        long id = register(head, WEB1);
        head.append(id, 1_000, 1);
        head.append(id, 2_000, 1); // chunk полон и закрыт, открыт новый — пустой
        assertThrows(OutOfOrderSampleException.class, () -> head.append(id, 1_500, 2));
    }

    // Порядок проверяет head до chunk.append: chunk не знает seriesId, в исключении нужен настоящий.
    @Test
    void outOfOrderReportsRealSeriesId() {
        MemHead head = head(2);
        register(head, Labels.of("cpu_usage", "host", "web-0"));
        long id = register(head, WEB1); // id = 2, не 0 и не 1
        head.append(id, 1_000, 1);
        head.append(id, 2_000, 1); // chunk закрыт — проверка идёт по lastTs серии, не по chunk'у
        OutOfOrderSampleException e = assertThrows(OutOfOrderSampleException.class, () -> head.append(id, 1_500, 2));
        assertEquals("series 2: ts 1500 <= last 2000", e.getMessage());
    }

    @Test
    void unknownSeriesRejected() {
        assertThrows(IllegalArgumentException.class, () -> head(4).append(42, 1, 1));
    }

    @Test
    void chunksFilteredByRange() {
        MemHead head = head(2);
        long id = register(head, WEB1);
        for (int i = 1; i <= 5; i++) {
            head.append(id, i * 10L, i); // chunk'и [10,20] [30,40] open [50]
        }
        assertEquals(1, head.chunks(id, 25, 35).size());
        assertEquals(2, head.chunks(id, 40, 100).size());
        assertEquals(0, head.chunks(id, 60, 100).size());
        assertEquals(List.of(), head.chunks(999, 0, 100));
    }

    @Test
    void chunksReturnSnapshotOfOpenChunk() {
        MemHead head = head(10);
        long id = register(head, WEB1);
        head.append(id, 1, 1);
        Chunk snapshot = head.chunks(id, 0, 100).get(0);
        head.append(id, 2, 2);
        assertEquals(List.of(new Sample(1, 1)), samplesOf(snapshot));
    }

    @Test
    void selectAndLabelsComeFromIndexAndRegistry() {
        MemHead head = head(4);
        long id = register(head, WEB1);
        register(head, Labels.of("cpu_usage", "host", "web-2"));
        PostingsIterator it = head.select(List.of(new Matcher.Eq("host", "web-1"))).iterator();
        assertTrue(it.next());
        assertEquals(id, it.at());
        assertFalse(it.next());
        assertEquals(WEB1, head.labels(id).orElseThrow());
    }

    @Test
    void truncateTakesChunksBeforeBoundary() {
        MemHead head = head(2);
        long a = register(head, WEB1);
        long b = register(head, Labels.of("cpu_usage", "host", "web-2"));
        for (int i = 1; i <= 5; i++) {
            head.append(a, i * 10L, i); // a: [10,20] [30,40] open [50]
        }
        head.append(b, 15, 1); // b: open [15] — целиком до границы, тоже уходит

        HeadSnapshot snap = head.truncate(45);
        List<SeriesChunks> series = snap.series().toList();
        assertEquals(2, series.size());
        assertEquals(a, series.get(0).series().id());
        assertEquals(2, series.get(0).chunks().size());
        assertEquals(b, series.get(1).series().id());
        assertEquals(10, snap.minTime());
        assertEquals(40, snap.maxTime());

        assertEquals(50, head.minTime());
        assertEquals(50, head.maxTime());
        assertEquals(1, head.chunks(a, 0, 100).size());
        assertEquals(0, head.chunks(b, 0, 100).size());
        head.append(b, 16, 2); // lastTs серии сохраняется
        assertThrows(OutOfOrderSampleException.class, () -> head.append(b, 15, 3));
    }

    @Test
    void truncateEmptyHeadGivesEmptySnapshot() {
        HeadSnapshot snap = head(4).truncate(1_000);
        assertEquals(0, snap.series().count());
        assertEquals(Long.MAX_VALUE, snap.minTime());
        assertEquals(Long.MIN_VALUE, snap.maxTime());
    }

    @Test
    void openChunkCrossingBoundaryIsSplit() {
        MemHead head = head(10);
        long id = register(head, WEB1);
        head.append(id, 10, 1);
        head.append(id, 60, 2);

        HeadSnapshot snap = head.truncate(50);
        List<Chunk> taken = snap.series().toList().get(0).chunks();
        assertEquals(List.of(new Sample(10, 1)), samplesOf(taken.get(0)));
        assertEquals(10, snap.maxTime());
        assertEquals(60, head.minTime());

        head.append(id, 70, 3); // хвост остался открытым chunk'ом — дописывается дальше
        List<Chunk> rest = head.chunks(id, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1, rest.size());
        assertEquals(List.of(new Sample(60, 2), new Sample(70, 3)), samplesOf(rest.get(0)));
        assertThrows(OutOfOrderSampleException.class, () -> head.append(id, 65, 4));
    }

    @Test
    void sealedChunkCrossingBoundaryIsSplit() {
        MemHead head = head(4);
        long id = register(head, WEB1);
        for (int i = 1; i <= 6; i++) {
            head.append(id, i * 10L, i); // закрытый [10..40], открытый [50, 60]
        }

        HeadSnapshot snap = head.truncate(25);
        List<Chunk> taken = snap.series().toList().get(0).chunks();
        assertEquals(1, taken.size());
        assertEquals(List.of(new Sample(10, 1), new Sample(20, 2)), samplesOf(taken.get(0)));
        assertEquals(30, head.minTime());
        assertEquals(List.of(new Sample(30, 3), new Sample(40, 4), new Sample(50, 5), new Sample(60, 6)),
                head.chunks(id, Long.MIN_VALUE, Long.MAX_VALUE).stream()
                        .flatMap(c -> samplesOf(c).stream()).toList());
    }

    @Test
    void concurrentAppendsToDifferentSeries() throws Exception {
        MemHead head = head(120);
        int threads = 8;
        int perThread = 10_000;
        long[] ids = IntStream.range(0, threads)
                .mapToLong(t -> register(head, Labels.of("m", "t", Integer.toString(t)))).toArray();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = IntStream.range(0, threads).<Future<?>>mapToObj(t -> pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    head.append(ids[t], i, i);
                }
                return null;
            })).toList();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        for (long id : ids) {
            int total = head.chunks(id, Long.MIN_VALUE, Long.MAX_VALUE).stream().mapToInt(Chunk::numSamples).sum();
            assertEquals(perThread, total);
        }
    }
}
