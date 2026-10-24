package org.tsdb.block;

import org.tsdb.model.Label;
import org.tsdb.model.Labels;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.CRC32;

/** Internal versioned on-disk block format shared by writer and reader. */
final class BlockFileFormat {
    static final int MAGIC = 0x54534442; // TSDB
    static final int VERSION = 1;
    static final int MAX_STRING = 1 << 20;
    static final int MAX_CHUNK = 32 << 20;

    private BlockFileFormat() {}

    record ChunkRef(long offset, int length, int crc, long minTime, long maxTime) {}
    record SeriesRef(long id, Labels labels, List<ChunkRef> chunks) {}

    static void writeString(DataOutput out, String value) throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_STRING) throw new IOException("String too large");
        out.writeInt(b.length);
        out.write(b);
    }

    static String readString(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_STRING) throw new IOException("Invalid string size: " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    static int crc(byte[] b) {
        CRC32 crc = new CRC32();
        crc.update(b);
        return (int) crc.getValue();
    }

    static void writeIndex(Path file, List<SeriesRef> series) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(series.size());
            for (SeriesRef s : series) {
                out.writeLong(s.id());
                out.writeInt(s.labels().labels().size());
                for (Label l : s.labels().labels()) {
                    writeString(out, l.name());
                    writeString(out, l.value());
                }
                out.writeInt(s.chunks().size());
                for (ChunkRef c : s.chunks()) {
                    out.writeLong(c.offset());
                    out.writeInt(c.length());
                    out.writeInt(c.crc());
                    out.writeLong(c.minTime());
                    out.writeLong(c.maxTime());
                }
            }
        }
    }

    static List<SeriesRef> readIndex(Path file) throws IOException {
        long size = Files.size(file);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unknown block index format");
            int count = in.readInt();
            if (count < 0 || count > size / 12) throw new IOException("Invalid series count");
            List<SeriesRef> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = in.readLong();
                if (id != i + 1L) throw new IOException("Non-contiguous block series IDs");
                int n = in.readInt();
                if (n < 0 || n > 100_000) throw new IOException("Invalid label count");
                List<Label> labels = new ArrayList<>();
                for (int j = 0; j < n; j++) labels.add(new Label(readString(in), readString(in)));
                int c = in.readInt();
                if (c < 0 || c > size / 28) throw new IOException("Invalid chunk count");
                List<ChunkRef> chunks = new ArrayList<>(c);
                for (int j = 0; j < c; j++) {
                    long offset = in.readLong();
                    int length = in.readInt();
                    int crc = in.readInt();
                    long min = in.readLong();
                    long max = in.readLong();
                    if (offset < 0 || length < 2 || length > MAX_CHUNK || min > max)
                        throw new IOException("Invalid chunk reference");
                    chunks.add(new ChunkRef(offset, length, crc, min, max));
                }
                result.add(new SeriesRef(id, new Labels(labels), List.copyOf(chunks)));
            }
            if (in.read() != -1) throw new IOException("Trailing index bytes");
            return result;
        }
    }

    static void writeMeta(Path path, BlockMeta m) throws IOException {
        StringBuilder s = new StringBuilder("{\n");
        s.append("  \"ulid\": \"").append(escape(m.ulid())).append("\",\n");
        s.append("  \"minTime\": ").append(m.minTime()).append(",\n");
        s.append("  \"maxTime\": ").append(m.maxTime()).append(",\n");
        s.append("  \"numSeries\": ").append(m.numSeries()).append(",\n");
        s.append("  \"numSamples\": ").append(m.numSamples()).append(",\n");
        s.append("  \"numChunks\": ").append(m.numChunks()).append(",\n");
        s.append("  \"compactionLevel\": ").append(m.compactionLevel()).append(",\n");
        s.append("  \"sources\": [");
        for (int i = 0; i < m.sources().size(); i++) {
            if (i != 0) s.append(", ");
            s.append('"').append(escape(m.sources().get(i))).append('"');
        }
        s.append("]\n}\n");
        Files.writeString(path, s.toString(), StandardCharsets.UTF_8);
    }

    // Read only metadata produced by this format; reject unexpected edits rather than guessing.
    static BlockMeta readMeta(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String id = get(text, "ulid", "\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
        long min = Long.parseLong(get(text, "minTime", "-?\\d+"));
        long max = Long.parseLong(get(text, "maxTime", "-?\\d+"));
        long series = Long.parseLong(get(text, "numSeries", "\\d+"));
        long samples = Long.parseLong(get(text, "numSamples", "\\d+"));
        long chunks = Long.parseLong(get(text, "numChunks", "\\d+"));
        int level = Integer.parseInt(get(text, "compactionLevel", "\\d+"));
        String array = get(text, "sources", "\\[[^\\]]*\\]");
        List<String> sources = new ArrayList<>();
        Matcher sm = Pattern.compile("\"([^\"]*)\"").matcher(array);
        while (sm.find()) sources.add(sm.group(1));
        return new BlockMeta(id, min, max, series, samples, chunks, level, sources);
    }

    private static String get(String text, String key, String valuePattern) throws IOException {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(" + valuePattern + ")").matcher(text);
        if (!m.find()) throw new IOException("Missing metadata: " + key);
        String value = m.group(1);
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length()-1);
        return value;
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
