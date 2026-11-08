package org.tsdb.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.Wal;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

// WAL в памяти. «Диск» — статическая мапа по каталогу: БД, переоткрытая в том же каталоге, видит те же записи.
public final class FakeWal implements Wal {

    private static final Map<Path, List<WalRecord>> DISK = new ConcurrentHashMap<>();

    private final List<WalRecord> records;
    private int syncs;
    private boolean closed;

    public FakeWal(Path dir) {
        this.records = records(dir);
    }

    // Записи журнала из каталога: тест может их посмотреть или подменить, как будто процесс упал.
    public static List<WalRecord> records(Path dir) {
        return DISK.computeIfAbsent(dir, d -> new ArrayList<>());
    }

    public synchronized int syncs() {
        return syncs;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized WalPosition append(WalRecord record) {
        checkOpen();
        records.add(record);
        return new WalPosition(1, records.size() - 1);
    }

    @Override
    public synchronized void sync() {
        checkOpen();
        syncs++;
    }

    @Override
    public synchronized void replay(Consumer<WalRecord> handler) {
        checkOpen();
        for (WalRecord record : List.copyOf(records)) {
            handler.accept(record);
        }
    }

    // Как checkpoint: SeriesCreated остаются, пачки, где все точки старше timestamp, удаляются.
    @Override
    public synchronized void truncateBefore(long timestamp) {
        checkOpen();
        records.removeIf(r -> r instanceof WalRecord.Samples s && allBefore(s.entries(), timestamp));
    }

    @Override
    public Stream<WalEntry> readFrom(WalPosition position) {
        throw new UnsupportedOperationException("lab4");
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private static boolean allBefore(List<SampleEntry> entries, long timestamp) {
        for (SampleEntry e : entries) {
            if (e.timestamp() >= timestamp) {
                return false;
            }
        }
        return true;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("WAL is closed");
        }
    }
}
