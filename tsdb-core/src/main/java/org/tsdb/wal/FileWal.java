
package org.tsdb.wal;

import org.tsdb.db.DbOptions;
import org.tsdb.db.DbPaths;
import org.tsdb.model.Label;
import org.tsdb.model.Labels;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static java.nio.file.StandardOpenOption.*;

public final class FileWal implements Wal {

    private static final long DEFAULT_SEGMENT_SIZE = 8L * 1024 * 1024;
    private static final int MAX_PAYLOAD = 32 * 1024 * 1024;
    private static final String CHECKPOINT_PREFIX = "checkpoint.";
    private static final String CHECKPOINT_TMP = "checkpoint.tmp";

    private final Path directory;
    private final long segmentSize;
    private final long syncIntervalMs;

    private final NavigableMap<Long, Long> maxTimestamps = new TreeMap<>();
    // Все серии, которые видел журнал (checkpoint, сегменты, append). Перед удалением сегментов
    // пишутся в checkpoint: иначе после рестарта id из Samples ничего бы не значили.
    private final NavigableMap<Long, Labels> series = new TreeMap<>();

    private FileChannel channel;
    private long currentSegment;
    private long lastSyncNanos;
    private boolean closed;

    public FileWal(DbOptions options) {
        this(DbPaths.of(options).walDir(),
                DEFAULT_SEGMENT_SIZE,
                options.walSyncIntervalMs());
    }

    public FileWal(DbPaths paths) {
        this(paths.walDir(), DEFAULT_SEGMENT_SIZE, 0);
    }

    public FileWal(Path directory) {
        this(directory, DEFAULT_SEGMENT_SIZE, 0);
    }

    public FileWal(Path directory, long segmentSize, long syncIntervalMs) {
        if (segmentSize < 9 || syncIntervalMs < 0) {
            throw new IllegalArgumentException("Invalid WAL configuration");
        }

        this.directory = Objects.requireNonNull(directory);
        this.segmentSize = segmentSize;
        this.syncIntervalMs = syncIntervalMs;

        try {
            Files.createDirectories(directory);
            // недописанный checkpoint: предыдущий цел, сегменты под ним не удалены
            Files.deleteIfExists(directory.resolve(CHECKPOINT_TMP));
            for (WalRecord record : readCheckpoint()) {
                if (record instanceof WalRecord.SeriesCreated(long id, Labels labels)) {
                    series.put(id, labels);
                }
            }

            List<Long> segments = listSegments();

            if (segments.isEmpty()) {
                currentSegment = 1;
            } else {
                for (int i = 0; i < segments.size(); i++) {
                    long id = segments.get(i);
                    scan(id, i == segments.size() - 1, null);
                }
                currentSegment = segments.get(segments.size() - 1);
            }

            openCurrent();
            lastSyncNanos = System.nanoTime();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open WAL", e);
        }
    }

    @Override
    public synchronized WalPosition append(WalRecord record) {
        checkOpen();
        Objects.requireNonNull(record, "record");

        byte[] payload = encode(record);

        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("WAL record too large");
        }

        int frameSize = Math.addExact(8, payload.length);

        try {
            if (channel.size() > 0 &&
                    channel.size() + frameSize > segmentSize) {
                rotate();
            }

            long offset = channel.position();
            WalPosition position = new WalPosition(currentSegment, offset);

            writeFrame(channel, payload);

            updateMetadata(currentSegment, record);

            long elapsedMs =
                    (System.nanoTime() - lastSyncNanos) / 1_000_000L;

            if (syncIntervalMs == 0 || elapsedMs >= syncIntervalMs) {
                sync();
            }

            return position;
        } catch (IOException e) {
            throw new UncheckedIOException("WAL append failed", e);
        }
    }

    @Override
    public synchronized void sync() {
        checkOpen();
        try {
            channel.force(true);
            lastSyncNanos = System.nanoTime();
        } catch (IOException e) {
            throw new UncheckedIOException("WAL sync failed", e);
        }
    }

    @Override
    public synchronized void replay(Consumer<WalRecord> handler) {
        Objects.requireNonNull(handler);
        checkOpen();

        // Сначала серии из checkpoint'а (по возрастанию id), потом все оставшиеся сегменты.
        // SeriesCreated может прийти дважды — из checkpoint'а и из ещё не удалённого сегмента.
        for (WalRecord record : readCheckpoint()) {
            handler.accept(record);
        }
        for (WalEntry entry : readAll()) {
            handler.accept(entry.record());
        }
    }

    @Override
    public synchronized Stream<WalEntry> readFrom(WalPosition position) {
        checkOpen();
        Objects.requireNonNull(position);

        List<WalEntry> entries = readAll();

        if (entries.isEmpty()) {
            if (position.segment() < currentSegment) {
                throw new IllegalStateException("WAL position expired");
            }
            if (position.segment() == currentSegment &&
                    position.offset() == 0) {
                return Stream.empty();
            }
            throw new IllegalArgumentException("Invalid WAL position");
        }

        WalPosition oldest = entries.get(0).position();

        if (position.compareTo(oldest) < 0) {
            throw new IllegalStateException("WAL position expired");
        }

        // Допускаем позицию непосредственно за последней записью.
        WalPosition end;
        try {
            end = new WalPosition(currentSegment, channel.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (position.equals(end)) {
            return Stream.empty();
        }

        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).position().equals(position)) {
                return entries.subList(i, entries.size()).stream();
            }
        }

        throw new IllegalArgumentException(
                "Position is not a record boundary: " + position);
    }

    @Override
    public synchronized void truncateBefore(long timestamp) {
        checkOpen();

        try {
            // Активный сегмент целиком устарел (например, после flush всего head) — переходим
            // на новый, чтобы старый можно было удалить вместе с остальными.
            if (channel.size() > 0 && maxTimestamps.get(currentSegment) < timestamp) {
                rotate();
            }

            List<Long> expired = new ArrayList<>();
            for (Map.Entry<Long, Long> e : maxTimestamps.entrySet()) {
                if (e.getKey() != currentSegment && e.getValue() < timestamp) {
                    expired.add(e.getKey());
                }
            }
            if (expired.isEmpty()) {
                return;
            }

            // Сначала checkpoint со всеми сериями, только потом удаление: упадём между — ничего не потеряно.
            writeCheckpoint();
            for (long segment : expired) {
                Files.deleteIfExists(segmentPath(segment));
                maxTimestamps.remove(segment);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot truncate WAL segment", e);
        }
    }

    // wal/checkpoint.NNNNNNNN (NNNNNNNN — активный сегмент на момент записи): записи SeriesCreated
    // по возрастанию id в том же формате, что и сегменты. Пишется во временный файл и атомарно
    // переименовывается; старые checkpoint'ы удаляются после.
    private void writeCheckpoint() throws IOException {
        Path tmp = directory.resolve(CHECKPOINT_TMP);
        try (FileChannel file = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            for (Map.Entry<Long, Labels> e : series.entrySet()) {
                writeFrame(file, encode(new WalRecord.SeriesCreated(e.getKey(), e.getValue())));
            }
            file.force(true);
        }
        Path target = checkpointPath(currentSegment);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        for (Path old : listCheckpoints()) {
            if (!old.equals(target)) {
                Files.deleteIfExists(old);
            }
        }
    }

    // Записи последнего checkpoint'а; нет checkpoint'а — пусто.
    private List<WalRecord> readCheckpoint() {
        try {
            List<Path> checkpoints = listCheckpoints();
            if (checkpoints.isEmpty()) {
                return List.of();
            }
            List<WalEntry> entries = new ArrayList<>();
            scan(checkpoints.get(checkpoints.size() - 1), -1, false, entries);
            return entries.stream().map(WalEntry::record).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read WAL checkpoint", e);
        }
    }

    private Path checkpointPath(long segment) {
        return directory.resolve(CHECKPOINT_PREFIX + String.format("%08d", segment));
    }

    // По возрастанию номера.
    private List<Path> listCheckpoints() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(p -> p.getFileName().toString().matches("checkpoint\\.[0-9]+"))
                    .sorted()
                    .toList();
        }
    }

    private static void writeFrame(FileChannel target, byte[] payload) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length);
        frame.putInt(payload.length);
        frame.putInt((int) crc.getValue());
        frame.put(payload);
        frame.flip();

        while (frame.hasRemaining()) {
            target.write(frame);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        try {
            channel.force(true);
            channel.close();
            closed = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close WAL", e);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("WAL is closed");
        }
    }

    private void openCurrent() throws IOException {
        channel = FileChannel.open(
                segmentPath(currentSegment),
                CREATE, READ, WRITE);
        channel.position(channel.size());
        maxTimestamps.putIfAbsent(currentSegment, Long.MIN_VALUE);
    }

    private void rotate() throws IOException {
        channel.force(true);
        channel.close();

        currentSegment = Math.addExact(currentSegment, 1);
        openCurrent();
    }

    private Path segmentPath(long id) {
        return directory.resolve(String.format("%08d", id));
    }

    private List<Long> listSegments() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("[0-9]+"))
                    .map(Long::parseLong)
                    .sorted()
                    .toList();
        }
    }

    private List<WalEntry> readAll() {
        try {
            List<WalEntry> result = new ArrayList<>();

            for (long id : listSegments()) {
                scan(id, false, result);
            }

            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read WAL", e);
        }
    }

    private void scan(
            long segment,
            boolean repairTail,
            List<WalEntry> output
    ) throws IOException {
        scan(segmentPath(segment), segment, repairTail, output);
    }

    // segment == -1 — checkpoint: метаданные сегментов не трогаем.
    private void scan(
            Path path,
            long segment,
            boolean repairTail,
            List<WalEntry> output
    ) throws IOException {

        byte[] data = Files.readAllBytes(path);

        int offset = 0;

        while (offset < data.length) {
            int remaining = data.length - offset;

            if (remaining < 8) {
                if (repairTail) {
                    repair(path, offset);
                    break;
                }
                throw new CorruptedWalException(
                        "Incomplete WAL header in " + path);
            }

            ByteBuffer header = ByteBuffer.wrap(data, offset, 8);
            int length = header.getInt();
            int storedCrc = header.getInt();

            if (length < 0 || length > MAX_PAYLOAD) {
                throw new CorruptedWalException(
                        "Invalid WAL record length in " + path);
            }

            if (remaining - 8 < length) {
                if (repairTail) {
                    repair(path, offset);
                    break;
                }
                throw new CorruptedWalException(
                        "Incomplete WAL payload in " + path);
            }

            byte[] payload = Arrays.copyOfRange(
                    data, offset + 8, offset + 8 + length);

            CRC32 crc = new CRC32();
            crc.update(payload);

            if ((int) crc.getValue() != storedCrc) {
                throw new CorruptedWalException(
                        "WAL CRC mismatch in " + path
                                + " at offset " + offset);
            }

            WalRecord record;
            try {
                record = decode(payload);
            } catch (RuntimeException e) {
                throw new CorruptedWalException(
                        "Invalid WAL record in " + path, e);
            }

            if (output != null) {
                output.add(new WalEntry(
                        new WalPosition(segment, offset), record));
            } else {
                updateMetadata(segment, record);
            }

            offset += 8 + length;
        }

        if (segment >= 0) {
            maxTimestamps.putIfAbsent(segment, Long.MIN_VALUE);
        }
    }

    private void repair(Path path, long validLength) throws IOException {
        try (FileChannel file = FileChannel.open(path, WRITE)) {
            file.truncate(validLength);
            file.force(true);
        }
    }

    private void updateMetadata(long segment, WalRecord record) {
        maxTimestamps.putIfAbsent(segment, Long.MIN_VALUE);

        if (record instanceof WalRecord.SeriesCreated created) {
            series.put(created.id(), created.labels());
        } else if (record instanceof WalRecord.Samples samples) {
            for (SampleEntry entry : samples.entries()) {
                maxTimestamps.merge(
                        segment, entry.timestamp(), Math::max);
            }
        }
    }

    private static byte[] encode(WalRecord record) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);

            if (record instanceof WalRecord.SeriesCreated created) {
                out.writeByte(1);
                out.writeLong(created.id());

                List<Label> labels = created.labels().labels();
                out.writeInt(labels.size());

                for (Label label : labels) {
                    writeString(out, label.name());
                    writeString(out, label.value());
                }
            } else if (record instanceof WalRecord.Samples samples) {
                out.writeByte(2);
                out.writeInt(samples.entries().size());

                for (SampleEntry entry : samples.entries()) {
                    out.writeLong(entry.seriesId());
                    out.writeLong(entry.timestamp());
                    out.writeLong(
                            Double.doubleToRawLongBits(entry.value()));
                }
            } else {
                throw new IllegalArgumentException(
                        "Unknown WAL record type");
            }

            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WalRecord decode(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(payload));

            int type = in.readUnsignedByte();
            WalRecord result;

            if (type == 1) {
                long id = in.readLong();
                int count = checkedCount(in.readInt(), in.available());

                List<Label> labels = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    labels.add(new Label(
                            readString(in), readString(in)));
                }

                result = new WalRecord.SeriesCreated(
                        id, new Labels(labels));
            } else if (type == 2) {
                int count = checkedCount(in.readInt(),
                        in.available() / 24);

                List<SampleEntry> entries = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    entries.add(new SampleEntry(
                            in.readLong(),
                            in.readLong(),
                            Double.longBitsToDouble(in.readLong())));
                }

                result = new WalRecord.Samples(entries);
            } else {
                throw new IllegalArgumentException(
                        "Unknown WAL record type: " + type);
            }

            if (in.available() != 0) {
                throw new IllegalArgumentException(
                        "Unexpected trailing WAL bytes");
            }

            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Invalid WAL payload", e);
        }
    }

    private static int checkedCount(int count, int maximum) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid WAL count");
        }
        return count;
    }

    private static void writeString(
            DataOutputStream out, String value
    ) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(
            DataInputStream in
    ) throws IOException {
        int length = in.readInt();

        if (length < 0 || length > in.available()) {
            throw new IOException("Invalid WAL string length");
        }

        byte[] bytes = new byte[length];
        in.readFully(bytes);

        return new String(
                bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
