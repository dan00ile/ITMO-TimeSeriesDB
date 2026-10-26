package org.tsdb.server.prometheus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SnappyTest {
    private static final int LIMIT = 1 << 20;

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // Векторы собраны вручную по format_description.txt
    @Test
    void literalOnly() {
        // длина 3, литерал из 3 байт: тег (3-1)<<2 = 0x08
        assertArrayEquals(ascii("abc"), Snappy.decompress(bytes(0x03, 0x08, 'a', 'b', 'c'), LIMIT));
    }

    @Test
    void copyWithOneByteOffsetOverlapping() {
        // "abcabcabc": литерал "abc", затем копия длиной 6 со смещением 3 (перекрывается сама с собой)
        // copy1: ((6-4)<<2) | 1 = 0x09, offset = 3
        assertArrayEquals(ascii("abcabcabc"),
                Snappy.decompress(bytes(0x09, 0x08, 'a', 'b', 'c', 0x09, 0x03), LIMIT));
    }

    @Test
    void copyWithTwoAndFourByteOffsets() {
        // "xyxyxy": литерал "xy", копия 4 байт со смещением 2
        // copy2: ((4-1)<<2) | 2 = 0x0E, offset little-endian 02 00
        assertArrayEquals(ascii("xyxyxy"), Snappy.decompress(bytes(0x06, 0x04, 'x', 'y', 0x0E, 0x02, 0x00), LIMIT));
        // copy4: ((4-1)<<2) | 3 = 0x0F, offset 02 00 00 00
        assertArrayEquals(ascii("xyxyxy"),
                Snappy.decompress(bytes(0x06, 0x04, 'x', 'y', 0x0F, 0x02, 0x00, 0x00, 0x00), LIMIT));
    }

    @Test
    void longLiteralLengths() {
        // литерал из 100 байт: длина-1 = 99 не влезает в тег → тег 60<<2 и 1 байт длины
        byte[] data = new byte[100];
        new Random(1).nextBytes(data);
        byte[] encoded = new byte[3 + 100];
        encoded[0] = 100;
        encoded[1] = (byte) (60 << 2);
        encoded[2] = 99;
        System.arraycopy(data, 0, encoded, 3, 100);
        assertArrayEquals(data, Snappy.decompress(encoded, LIMIT));
    }

    @Test
    void compressRoundTripsForAllLiteralSizes() {
        Random random = new Random(2);
        for (int size : new int[] {0, 1, 59, 60, 61, 255, 256, 257, 65_535, 65_536, 70_000, 300_000}) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            assertArrayEquals(data, Snappy.decompress(Snappy.compress(data), LIMIT), "size " + size);
        }
    }

    @Test
    void corruptInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> Snappy.decompress(bytes(), LIMIT));
        assertThrows(IllegalArgumentException.class, () -> Snappy.decompress(bytes(0x05, 0x08, 'a'), LIMIT));
        // копия раньше начала данных
        assertThrows(IllegalArgumentException.class, () -> Snappy.decompress(bytes(0x06, 0x09, 0x03), LIMIT));
        // данных меньше, чем обещает заголовок
        assertThrows(IllegalArgumentException.class, () -> Snappy.decompress(bytes(0x04, 0x08, 'a', 'b', 'c'), LIMIT));
        // заголовок больше лимита
        assertThrows(IllegalArgumentException.class, () -> Snappy.decompress(bytes(0x80, 0x80, 0x80, 0x01), 1000));
    }
}
