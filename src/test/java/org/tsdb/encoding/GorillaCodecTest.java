package org.tsdb.encoding;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GorillaCodecTest {

    @Test
    @Disabled("lab2")
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

    private ChunkCodec codec() {
        throw new UnsupportedOperationException("lab2");
    }
}