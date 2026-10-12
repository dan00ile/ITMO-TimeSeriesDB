package org.tsdb.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import org.tsdb.block.Block;
import org.tsdb.block.BlockMeta;
import org.tsdb.block.BlockReader;
import org.tsdb.block.BlockWriter;
import org.tsdb.compact.CompactionPlanner;
import org.tsdb.compact.RetentionPolicy;
import org.tsdb.encoding.Chunk;
import org.tsdb.head.Head;
import org.tsdb.head.HeadSnapshot;
import org.tsdb.index.MemSeriesRegistry;
import org.tsdb.model.Labels;
import org.tsdb.model.OutOfOrderSampleException;
import org.tsdb.model.Query;
import org.tsdb.model.QueryResult;
import org.tsdb.model.Series;
import org.tsdb.query.QueryEngine;
import org.tsdb.query.Queryable;
import org.tsdb.wal.CorruptedWalException;
import org.tsdb.wal.SampleEntry;
import org.tsdb.wal.Wal;
import org.tsdb.wal.WalRecord;

/**
 * Одноузловая БД: связывает слои и держит порядок «WAL раньше памяти». Сама не сжимает,
 * не индексирует и не агрегирует. В лабах 4-5 её обернут Replicator и ShardRouter.
 * Блокировка одна: запись, flush, компакция и удаление идут по очереди, запросы — параллельно.
 */
public final class LocalTimeSeriesDB implements TimeSeriesDB {

    private static final System.Logger LOG = System.getLogger(LocalTimeSeriesDB.class.getName());
    private static final char[] ULID_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final DbOptions options;
    private final DbPaths paths;
    private final Head head;
    private final BlockWriter blockWriter;
    private final BlockReader blockReader;
    private final QueryEngine queryEngine;
    private final CompactionPlanner compactionPlanner;
    private final RetentionPolicy retentionPolicy;
    private final Wal wal;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    // по возрастанию minTime — в таком порядке их ждёт QueryEngine
    private final List<Block> blocks = new ArrayList<>();
    // последний timestamp каждой серии: порядок проверяем до записи в WAL, чтобы там не было отклонённых точек
    private final Map<Long, Long> lastTimestamps = new HashMap<>();
    // всё, что не новее, уже лежит в блоках
    private long blocksMaxTime = Long.MIN_VALUE;
    private ScheduledExecutorService scheduler;
    private boolean closed;

    private LocalTimeSeriesDB(DbOptions options, DbComponents components) {
        this.options = options;
        this.paths = DbPaths.of(options);
        createDirectories(paths.walDir());
        createDirectories(paths.blocksDir());
        // в tmp/ могли остаться недописанные блоки: данные из них всё ещё в WAL
        deleteRecursively(paths.tmpDir());
        createDirectories(paths.tmpDir());
        this.head = components.newHead(options);
        this.blockWriter = components.blockWriter(options);
        this.blockReader = components.blockReader(options);
        this.queryEngine = components.queryEngine(options);
        this.compactionPlanner = components.compactionPlanner(options);
        this.retentionPolicy = components.retentionPolicy(options);
        this.wal = components.openWal(options);
    }

    public static LocalTimeSeriesDB open(DbOptions options) {
        return open(options, new DefaultComponents());
    }

    // Фабрика, а не конструктор: восстановление из WAL и открытие блоков могут упасть.
    public static LocalTimeSeriesDB open(DbOptions options, DbComponents components) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(components, "components");
        LocalTimeSeriesDB db = new LocalTimeSeriesDB(options, components);
        try {
            db.start();
        } catch (RuntimeException e) {
            try {
                db.close();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return db;
    }

    public DbOptions options() {
        return options;
    }

    public DbPaths paths() {
        return paths;
    }

    @Override
    public void append(Labels labels, long timestamp, double value) {
        appendBatch(List.of(new SampleInput(labels, timestamp, value)));
    }

    @Override
    public void appendBatch(List<SampleInput> batch) {
        Objects.requireNonNull(batch, "batch");
        lock.writeLock().lock();
        try {
            checkOpen();
            List<SampleEntry> entries = new ArrayList<>();
            RuntimeException error = null;
            for (SampleInput input : batch) {
                try {
                    entries.add(prepare(input));
                } catch (RuntimeException e) {
                    error = e;
                    break;
                }
            }
            if (!entries.isEmpty()) {
                wal.append(new WalRecord.Samples(entries));
                if (options.walSyncIntervalMs() == 0) {
                    wal.sync();
                }
                for (SampleEntry e : entries) {
                    head.append(e.seriesId(), e.timestamp(), e.value());
                }
            }
            if (error != null) {
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public QueryResult query(Query query) {
        Objects.requireNonNull(query, "query");
        lock.readLock().lock();
        try {
            checkOpen();
            List<Queryable> sources = new ArrayList<>();
            for (Block block : blocks) {
                if (overlaps(block, query)) {
                    sources.add(block);
                }
            }
            if (overlaps(head, query)) {
                sources.add(head);
            }
            return queryEngine.execute(query, sources);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> labelNames() {
        lock.readLock().lock();
        try {
            checkOpen();
            Set<String> names = new TreeSet<>(head.index().labelNames());
            for (Block block : blocks) {
                names.addAll(block.index().labelNames());
            }
            return names;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> labelValues(String name) {
        Objects.requireNonNull(name, "name");
        lock.readLock().lock();
        try {
            checkOpen();
            Set<String> values = new TreeSet<>(head.index().labelValues(name));
            for (Block block : blocks) {
                values.addAll(block.index().labelValues(name));
            }
            return values;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void flush() {
        lock.writeLock().lock();
        try {
            checkOpen();
            if (!headIsEmpty()) {
                cutHead(head.maxTime() + 1);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void compact() {
        lock.writeLock().lock();
        try {
            checkOpen();
            compactOnce();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void deleteBefore(long timestamp) {
        lock.writeLock().lock();
        try {
            checkOpen();
            List<Block> old = new ArrayList<>();
            for (Block block : blocks) {
                if (block.maxTime() < timestamp) {
                    old.add(block);
                }
            }
            removeBlocks(old);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public DbStats stats() {
        lock.readLock().lock();
        try {
            checkOpen();
            // одна серия может лежать и в head, и в нескольких блоках — считаем по Labels
            Set<Labels> series = new HashSet<>();
            long headSamples = 0;
            for (Series s : head.registry().all().toList()) {
                series.add(s.labels());
                for (Chunk chunk : head.chunks(s.id(), Long.MIN_VALUE, Long.MAX_VALUE)) {
                    headSamples += chunk.numSamples();
                }
            }
            for (Block block : blocks) {
                for (Series s : block.registry().all().toList()) {
                    series.add(s.labels());
                }
            }
            return new DbStats(series.size(), blocks.size(), headSamples, diskBytes());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (scheduler != null) {
                scheduler.shutdown();
            }
            try {
                wal.sync();
                wal.close();
            } finally {
                for (Block block : blocks) {
                    block.close();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Один проход фонового обслуживания. Виден из пакета, чтобы тесты не ждали планировщик.
    void runMaintenance() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            cutHeadIfNeeded();
            applyRetention();
            compactOnce();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void start() {
        lock.writeLock().lock();
        try {
            loadBlocks();
            replayWal();
            startBackgroundTasks();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadBlocks() {
        try (Stream<Path> dirs = Files.list(paths.blocksDir())) {
            for (Path dir : dirs.toList()) {
                if (Files.isDirectory(dir)) {
                    blocks.add(blockReader.open(dir));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        removeReplacedBlocks();
        sortBlocks();
        for (Block block : blocks) {
            blocksMaxTime = Math.max(blocksMaxTime, block.maxTime());
        }
    }

    // Упали посреди компакции: слитый блок уже в blocks/, а исходные ещё не удалены.
    private void removeReplacedBlocks() {
        List<Block> replaced = new ArrayList<>();
        for (Block block : blocks) {
            for (Block other : blocks) {
                if (other != block && other.meta().sources().contains(block.meta().ulid())) {
                    replaced.add(block);
                    break;
                }
            }
        }
        removeBlocks(replaced);
    }

    private void replayWal() {
        wal.replay(record -> {
            switch (record) {
                case WalRecord.SeriesCreated created -> restoreSeries(created.id(), created.labels());
                case WalRecord.Samples samples -> {
                    for (SampleEntry e : samples.entries()) {
                        restoreSample(e);
                    }
                }
            }
        });
    }

    // Серия получает ровно id из журнала: после truncateBefore первые SeriesCreated уже удалены
    // (их заменяет checkpoint), и getOrCreate выдал бы другие номера.
    private void restoreSeries(long id, Labels labels) {
        OptionalLong known = head.registry().get(labels);
        if (head.registry() instanceof MemSeriesRegistry registry) {
            try {
                registry.restore(id, labels);
            } catch (IllegalStateException e) {
                throw new CorruptedWalException("series " + labels + " with id " + id + ": " + e.getMessage(), e);
            }
        } else {
            // заглушки без restore: id совпадут, только если журнал не обрезали
            long actual = known.isPresent() ? known.getAsLong() : head.registry().getOrCreate(labels);
            if (actual != id) {
                throw new CorruptedWalException("series " + labels + " has id " + id + " in WAL, got " + actual);
            }
        }
        if (known.isEmpty()) {
            head.index().add(id, labels);
        }
    }

    private void restoreSample(SampleEntry e) {
        if (head.registry().labels(e.seriesId()).isEmpty()) {
            throw new CorruptedWalException("sample of unknown series " + e.seriesId());
        }
        // точка уже в блоке: упали между записью блока и обрезкой WAL
        if (e.timestamp() <= lastTimestamp(e.seriesId())) {
            return;
        }
        head.append(e.seriesId(), e.timestamp(), e.value());
        lastTimestamps.put(e.seriesId(), e.timestamp());
    }

    private SampleEntry prepare(SampleInput input) {
        Labels labels = input.labels();
        if (labels.metric().orElse("").isEmpty()) {
            throw new IllegalArgumentException("series without " + Labels.METRIC_NAME + ": " + labels);
        }
        OptionalLong known = head.registry().get(labels);
        long last = known.isPresent() ? lastTimestamp(known.getAsLong()) : blocksMaxTime;
        if (input.timestamp() <= last) {
            throw new OutOfOrderSampleException(known.orElse(-1), input.timestamp(), last);
        }
        long id = known.isPresent() ? known.getAsLong() : createSeries(labels);
        lastTimestamps.put(id, input.timestamp());
        return new SampleEntry(id, input.timestamp(), input.value());
    }

    // Новая серия попадает в журнал раньше своей первой точки.
    private long createSeries(Labels labels) {
        long id = head.registry().getOrCreate(labels);
        wal.append(new WalRecord.SeriesCreated(id, labels));
        head.index().add(id, labels);
        return id;
    }

    // Следующая точка серии должна быть строго новее.
    private long lastTimestamp(long seriesId) {
        Long last = lastTimestamps.get(seriesId);
        return last == null ? blocksMaxTime : Math.max(last, blocksMaxTime);
    }

    // Head держит не больше полутора окон: самое старое целое окно уходит в блок.
    private void cutHeadIfNeeded() {
        long range = options.blockRangeMs();
        if (headIsEmpty() || head.maxTime() - head.minTime() <= range + range / 2) {
            return;
        }
        cutHead(Math.floorDiv(head.minTime(), range) * range + range);
    }

    // Сначала блок целиком появляется в blocks/, только потом обрезаем WAL: упадём между — данные не потеряются.
    private void cutHead(long upTo) {
        HeadSnapshot snapshot = head.truncate(upTo);
        if (snapshot.minTime() > snapshot.maxTime()) {
            return;
        }
        Path tmp = paths.tmpDir().resolve(newUlid());
        publishBlock(tmp, blockWriter.write(snapshot, tmp));
        // head.truncate режет точно по upTo: всё, что раньше, уже в блоке
        wal.truncateBefore(upTo);
    }

    // «Сейчас» — самая свежая точка в БД, а не часы, как в Prometheus: импорт старых данных не удалится сразу.
    private void applyRetention() {
        if (options.retentionMs() == 0 || blocks.isEmpty()) {
            return;
        }
        long now = Math.max(head.maxTime(), blocksMaxTime);
        removeBlocks(blocksOf(retentionPolicy.expired(blockMetas(), now)));
    }

    private void compactOnce() {
        for (List<BlockMeta> group : compactionPlanner.plan(blockMetas())) {
            List<Block> parts = blocksOf(group);
            if (parts.size() < 2) {
                continue;
            }
            Path tmp = paths.tmpDir().resolve(newUlid());
            publishBlock(tmp, blockWriter.merge(parts, tmp));
            removeBlocks(parts);
        }
    }

    // tmp/<ulid> -> blocks/<ulid> одним переименованием: блок либо есть целиком, либо его нет.
    private void publishBlock(Path tmp, BlockMeta meta) {
        Path dir = paths.blockDir(meta.ulid());
        try {
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        blocks.add(blockReader.open(dir));
        sortBlocks();
        blocksMaxTime = Math.max(blocksMaxTime, meta.maxTime());
    }

    private void removeBlocks(List<Block> toRemove) {
        for (Block block : toRemove) {
            blocks.remove(block);
            block.close();
            deleteRecursively(block.dir());
        }
    }

    private List<BlockMeta> blockMetas() {
        List<BlockMeta> metas = new ArrayList<>();
        for (Block block : blocks) {
            metas.add(block.meta());
        }
        return metas;
    }

    private List<Block> blocksOf(List<BlockMeta> metas) {
        List<Block> result = new ArrayList<>();
        for (BlockMeta meta : metas) {
            for (Block block : blocks) {
                if (block.meta().ulid().equals(meta.ulid())) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    private void sortBlocks() {
        blocks.sort(Comparator.comparingLong(Block::minTime));
    }

    private void startBackgroundTasks() {
        long syncInterval = options.walSyncIntervalMs();
        long maintenanceInterval = options.compactionIntervalMs();
        if (syncInterval == 0 && maintenanceInterval == 0) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tsdb-background");
            thread.setDaemon(true);
            return thread;
        });
        if (syncInterval > 0) {
            scheduler.scheduleWithFixedDelay(
                    logErrors("WAL sync", this::syncWal), syncInterval, syncInterval, TimeUnit.MILLISECONDS);
        }
        if (maintenanceInterval > 0) {
            scheduler.scheduleWithFixedDelay(
                    logErrors("maintenance", this::runMaintenance),
                    maintenanceInterval, maintenanceInterval, TimeUnit.MILLISECONDS);
        }
    }

    private void syncWal() {
        lock.readLock().lock();
        try {
            if (!closed) {
                wal.sync();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // Исключение из периодической задачи молча отменяет все её следующие запуски.
    private static Runnable logErrors(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "background " + name + " failed", e);
            }
        };
    }

    private boolean headIsEmpty() {
        return head.minTime() > head.maxTime();
    }

    private static boolean overlaps(Queryable source, Query query) {
        return source.minTime() <= query.end() && source.maxTime() >= query.start();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("database is closed");
        }
    }

    private long diskBytes() {
        try (Stream<Path> files = Files.walk(paths.dataDir())) {
            long total = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                total += Files.size(file);
            }
            return total;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            // сначала содержимое, потом сам каталог
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ULID: 10 символов времени + 16 случайных, имена блоков сортируются по времени создания.
    private static String newUlid() {
        char[] out = new char[26];
        long time = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ULID_CHARS[(int) (time & 31)];
            time >>>= 5;
        }
        Random random = ThreadLocalRandom.current();
        for (int i = 10; i < out.length; i++) {
            out[i] = ULID_CHARS[random.nextInt(32)];
        }
        return new String(out);
    }
}
