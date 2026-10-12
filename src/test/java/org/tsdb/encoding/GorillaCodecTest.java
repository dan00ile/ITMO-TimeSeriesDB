package org.tsdb.encoding;

import org.junit.jupiter.api.Test;

import org.tsdb.model.OutOfOrderSampleException;

import static org.junit.jupiter.api.Assertions.*;

class GorillaCodecTest {

    @Test
    void roundtripRegular15sSeries() {
        ChunkCodec codec = codec();

        AppendableChunk chunk = codec.newAppendable();

        chunk.append(1_000L, 0.41);
        chunk.append(16_000L, 0.43);
        chunk.append(31_000L, 0.42);

        Chunk sealed = chunk.seal();

        Chunk decoded = codec.decode(sealed.bytes());

        assertEquals(3, decoded.numSamples());

        SampleIterator iterator = decoded.iterator();

        assertTrue(iterator.next());
        assertEquals(1_000L, iterator.timestamp());
        assertEquals(0.41, iterator.value());

        assertTrue(iterator.next());
        assertEquals(16_000L, iterator.timestamp());
        assertEquals(0.43, iterator.value());

        assertTrue(iterator.next());
        assertEquals(31_000L, iterator.timestamp());
        assertEquals(0.42, iterator.value());

        assertFalse(iterator.next());
    }

    @Test
    void roundtripFullWidthXor() {
        ChunkCodec codec = codec();
        AppendableChunk chunk = codec.newAppendable();

        chunk.append(1000L, 1.0);
        chunk.append(2000L, -1.0);
        chunk.append(3000L, 1.0);

        Chunk decoded = codec.decode(chunk.seal().bytes());
        SampleIterator iterator = decoded.iterator();

        assertTrue(iterator.next());
        assertEquals(1.0, iterator.value());

        assertTrue(iterator.next());
        assertEquals(-1.0, iterator.value());

        assertTrue(iterator.next());
        assertEquals(1.0, iterator.value());

        assertFalse(iterator.next());
    }

    @Test
    void roundtripSpecialDoubleValues() {
        ChunkCodec codec = codec();
        AppendableChunk chunk = codec.newAppendable();

        double[] values = {
                0.0,
                -0.0,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NaN,
                Double.longBitsToDouble(0x7ff8000000000001L),
                42.5
        };

        for (int i = 0; i < values.length; i++) {
            chunk.append(1000L + i * 15000L, values[i]);
        }

        Chunk decoded = codec.decode(chunk.seal().bytes());
        SampleIterator iterator = decoded.iterator();

        for (int i = 0; i < values.length; i++) {
            assertTrue(iterator.next());

            long expectedBits = Double.doubleToRawLongBits(values[i]);
            long actualBits = Double.doubleToRawLongBits(iterator.value());

            assertEquals(expectedBits, actualBits);
        }

        assertFalse(iterator.next());
    }

    @Test
    void roundtripDeltaOfDeltaBoundaries() {
        long[] dodValues = {
                0,
                -8192, 8191,
                -8193, 8192,
                -65536, 65535,
                -65537, 65536,
                -524288, 524287,
                -524289, 524288
        };

        ChunkCodec codec = codec();

        for (long dod : dodValues) {
            AppendableChunk chunk = codec.newAppendable();

            long t0 = 1_000_000L;
            long firstDelta = 1_000_000L;

            long t1 = t0 + firstDelta;
            long secondDelta = firstDelta + dod;
            long t2 = t1 + secondDelta;

            chunk.append(t0, 10.0);
            chunk.append(t1, 11.0);
            chunk.append(t2, 12.0);

            Chunk decoded = codec.decode(chunk.seal().bytes());
            SampleIterator iterator = decoded.iterator();

            assertTrue(iterator.next());
            assertEquals(t0, iterator.timestamp());

            assertTrue(iterator.next());
            assertEquals(t1, iterator.timestamp());

            assertTrue(iterator.next());
            assertEquals(t2, iterator.timestamp(),
                    "Failed for delta-of-delta = " + dod);

            assertFalse(iterator.next());
        }
    }

    @Test
    void chunkCapacityAndSeal() {
        ChunkCodec codec = new GorillaCodec(3);
        AppendableChunk chunk = codec.newAppendable();

        chunk.append(1000, 1.0);
        chunk.append(2000, 2.0);

        assertFalse(chunk.isFull());

        chunk.append(3000, 3.0);

        assertTrue(chunk.isFull());
        assertEquals(3, chunk.numSamples());

        Chunk sealed = chunk.seal();

        assertEquals(3, sealed.numSamples());
        assertEquals(1000L, sealed.minTime());
        assertEquals(3000L, sealed.maxTime());

        assertThrows(IllegalStateException.class,
                () -> chunk.append(4000, 4.0));
    }

    @Test
    void xorWindowReuse() {
        ChunkCodec codec = codec();
        AppendableChunk chunk = codec.newAppendable();

        long[] bits = {
                0x4028000000000000L,
                0x4029000000000000L,
                0x4028000000000000L,
                0x4028000000000000L
        };

        for (int i = 0; i < bits.length; i++) {
            chunk.append(
                    1000L + i * 15000L,
                    Double.longBitsToDouble(bits[i])
            );
        }

        SampleIterator iterator = codec.decode(
                chunk.seal().bytes()
        ).iterator();

        for (int i = 0; i < bits.length; i++) {
            assertTrue(iterator.next());

            assertEquals(
                    bits[i],
                    Double.doubleToRawLongBits(iterator.value())
            );
        }

        assertFalse(iterator.next());
    }

    @Test
    void emptyChunkRoundtrip() {
        ChunkCodec codec = codec();

        Chunk sealed = codec.newAppendable().seal();
        Chunk decoded = codec.decode(sealed.bytes());

        assertEquals(0, decoded.numSamples());
        assertFalse(decoded.iterator().next());
    }

    @Test
    void compressionRatioForRegularSeries() {
        ChunkCodec codec = new GorillaCodec(120);

        int totalSamples = 10_000;
        long timestamp = 1_000_000L;
        long compressedBytes = 0;

        AppendableChunk chunk = codec.newAppendable();

        for (int i = 0; i < totalSamples; i++) {
            if (chunk.isFull()) {
                compressedBytes += chunk.seal().bytes().length;
                chunk = codec.newAppendable();
            }

            chunk.append(timestamp, 42.0);
            timestamp += 15_000;
        }

        if (chunk.numSamples() > 0) {
            compressedBytes += chunk.seal().bytes().length;
        }

        double bytesPerSample = (double) compressedBytes / totalSamples;

        System.out.printf(
                "Gorilla: %.3f bytes/sample%n",
                bytesPerSample
        );

        assertTrue(
                bytesPerSample < 2.0,
                "Expected < 2 bytes/sample, got " + bytesPerSample
        );
    }

    @Test
    void rejectsOutOfOrderSamples() {
        AppendableChunk chunk = codec().newAppendable();

        chunk.append(1000L, 1.0);

        assertThrows(
                OutOfOrderSampleException.class,
                () -> chunk.append(1000L, 2.0)
        );

        assertThrows(
                OutOfOrderSampleException.class,
                () -> chunk.append(999L, 3.0)
        );

        assertEquals(1, chunk.numSamples());
    }

    private ChunkCodec codec() {
        return new GorillaCodec();
    }
}