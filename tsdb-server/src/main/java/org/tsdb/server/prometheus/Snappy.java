package org.tsdb.server.prometheus;

import java.io.ByteArrayOutputStream;

// Snappy block format — тело запросов и ответов remote read у Prometheus.
// https://github.com/google/snappy/blob/main/format_description.txt
//   [varint: длина исходных данных] затем элементы:
//   тег & 3 == 0 — литерал (байты как есть), 1/2/3 — копия уже распакованных байт по смещению 1/2/4 байта.
// decompress понимает весь формат. compress пишет данные одним литералом без поиска повторов:
// это корректный snappy, просто без сжатия — на localhost между Prometheus и БД экономить нечего.
final class Snappy {
    private Snappy() {
    }

    static byte[] decompress(byte[] in, int maxLength) {
        int[] ip = {0};
        long length = readVarint(in, ip);
        if (length > maxLength) {
            throw new IllegalArgumentException("snappy: uncompressed length " + length + " > limit " + maxLength);
        }
        byte[] out = new byte[(int) length];
        int op = 0;
        int i = ip[0];
        while (i < in.length) {
            int tag = in[i++] & 0xFF;
            int len;
            int offset;
            switch (tag & 3) {
                case 0 -> {
                    len = tag >>> 2;
                    if (len >= 60) {
                        int bytes = len - 59;
                        need(in, i, bytes);
                        len = (int) readLittleEndian(in, i, bytes);
                        i += bytes;
                    }
                    len += 1;
                    need(in, i, len);
                    if (len > out.length - op) {
                        throw corrupt("literal past end");
                    }
                    System.arraycopy(in, i, out, op, len);
                    i += len;
                    op += len;
                    continue;
                }
                case 1 -> {
                    need(in, i, 1);
                    len = ((tag >>> 2) & 7) + 4;
                    offset = ((tag >>> 5) << 8) | (in[i++] & 0xFF);
                }
                case 2 -> {
                    need(in, i, 2);
                    len = (tag >>> 2) + 1;
                    offset = (int) readLittleEndian(in, i, 2);
                    i += 2;
                }
                default -> {
                    need(in, i, 4);
                    len = (tag >>> 2) + 1;
                    long off = readLittleEndian(in, i, 4);
                    if (off > Integer.MAX_VALUE) {
                        throw corrupt("offset too large");
                    }
                    offset = (int) off;
                    i += 4;
                }
            }
            if (offset <= 0 || offset > op || len > out.length - op) {
                throw corrupt("bad copy offset " + offset + " at " + op);
            }
            // побайтно: источник и приёмник могут перекрываться (повтор короткого шаблона)
            for (int k = 0; k < len; k++) {
                out[op + k] = out[op - offset + k];
            }
            op += len;
        }
        if (op != out.length) {
            throw corrupt("got " + op + " bytes, header says " + out.length);
        }
        return out;
    }

    static byte[] compress(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 10);
        writeVarint(out, data.length);
        if (data.length == 0) {
            return out.toByteArray();
        }
        int n = data.length - 1;
        if (n < 60) {
            out.write(n << 2);
        } else {
            int bytes = n < (1 << 8) ? 1 : n < (1 << 16) ? 2 : n < (1 << 24) ? 3 : 4;
            out.write((59 + bytes) << 2);
            for (int k = 0; k < bytes; k++) {
                out.write((n >>> (8 * k)) & 0xFF);
            }
        }
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    private static long readVarint(byte[] in, int[] ip) {
        long value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            need(in, ip[0], 1);
            int b = in[ip[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw corrupt("varint too long");
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static long readLittleEndian(byte[] in, int at, int bytes) {
        long v = 0;
        for (int k = 0; k < bytes; k++) {
            v |= (long) (in[at + k] & 0xFF) << (8 * k);
        }
        return v;
    }

    private static void need(byte[] in, int at, int bytes) {
        if (at + bytes > in.length) {
            throw corrupt("truncated input");
        }
    }

    private static IllegalArgumentException corrupt(String why) {
        return new IllegalArgumentException("snappy: corrupt input: " + why);
    }
}
