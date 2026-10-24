package org.tsdb.encoding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteBitWriterTest {

    @Test
    void singleBitsRoundtrip() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBit(true);
        writer.writeBit(false);
        writer.writeBit(true);

        assertEquals(3, writer.bitLength());

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertTrue(reader.readBit());
        assertFalse(reader.readBit());
        assertTrue(reader.readBit());

        assertEquals(0, reader.remaining());
    }

    @Test
    void multipleBitsRoundtrip() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(5, 3);
        writer.writeBits(25, 5);

        assertEquals(8, writer.bitLength());

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertEquals(5, reader.readBits(3));
        assertEquals(25, reader.readBits(5));
        assertEquals(0, reader.remaining());
    }

    @Test
    void crossesByteBoundary() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(5, 3);
        writer.writeBits(1023, 10);

        assertEquals(13, writer.bitLength());

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertEquals(5, reader.readBits(3));
        assertEquals(1023, reader.readBits(10));
        assertEquals(0, reader.remaining());
    }

    @Test
    void full64BitRoundtrip() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(-1L, 64);
        writer.writeBits(Long.MIN_VALUE, 64);
        writer.writeBits(Long.MAX_VALUE, 64);

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertEquals(-1L, reader.readBits(64));
        assertEquals(Long.MIN_VALUE, reader.readBits(64));
        assertEquals(Long.MAX_VALUE, reader.readBits(64));

        assertEquals(0, reader.remaining());
    }

    @Test
    void byteRoundtrip() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeByte(255);
        writer.writeByte(128);
        writer.writeByte(0);

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertEquals(255, reader.readByte());
        assertEquals(128, reader.readByte());
        assertEquals(0, reader.readByte());
    }

    @Test
    void ignoresPaddingBits() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(5, 3);

        byte[] data = writer.toByteArray();

        assertEquals(1, data.length);
        assertEquals(3, writer.bitLength());

        ByteBitReader reader = new ByteBitReader(
                data,
                writer.bitLength()
        );

        assertEquals(5, reader.readBits(3));
        assertEquals(0, reader.remaining());

        assertThrows(
                IllegalStateException.class,
                reader::readBit
        );
    }

    @Test
    void rejectsInvalidBitCounts() {
        ByteBitWriter writer = new ByteBitWriter();

        assertThrows(
                IllegalArgumentException.class,
                () -> writer.writeBits(1, -1)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> writer.writeBits(1, 65)
        );

        ByteBitReader reader = new ByteBitReader(
                new byte[8]
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> reader.readBits(65)
        );
    }

    @Test
    void rejectsReadingPastEnd() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(5, 3);

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray(),
                writer.bitLength()
        );

        assertThrows(
                IllegalStateException.class,
                () -> reader.readBits(4)
        );

        // Неудачное чтение не должно менять позицию.
        assertEquals(3, reader.remaining());

        assertEquals(5, reader.readBits(3));
    }

    @Test
    void emptyWriter() {
        ByteBitWriter writer = new ByteBitWriter();

        assertEquals(0, writer.bitLength());
        assertArrayEquals(new byte[0], writer.toByteArray());

        ByteBitReader reader = new ByteBitReader(
                writer.toByteArray()
        );

        assertEquals(0, reader.remaining());
    }

    @Test
    void writesExpectedBytes() {
        ByteBitWriter writer = new ByteBitWriter();

        writer.writeBits(5, 3);
        writer.writeBits(25, 5);

        assertArrayEquals(
                new byte[]{(byte) 0b10111001},
                writer.toByteArray()
        );
    }
}