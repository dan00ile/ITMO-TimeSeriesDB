
package org.tsdb.cluster;

import org.tsdb.wal.WalPosition;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

import static java.nio.file.StandardOpenOption.*;

/**
 * Хранит последнюю надёжно применённую позицию
 * WAL лидера на стороне фолловера.
 *
 * Вызывать save() только после sync() локального WAL.
 */
public final class ReplicationStateStore {

    private static final String FILE_NAME = "replication.state";
    private static final String TMP_NAME = "replication.state.tmp";
    private static final int VERSION = 1;

    private final Path stateFile;
    private final Path tmpFile;

    public ReplicationStateStore(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir");

        this.stateFile = dataDir.resolve(FILE_NAME);
        this.tmpFile = dataDir.resolve(TMP_NAME);
    }

    /**
     * Читает последнюю сохранённую позицию лидера.
     * Если файла ещё нет, возвращает Optional.empty().
     */
    public synchronized Optional<WalPosition> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }

        try {
            if (Files.size(stateFile) != 20) {
                throw new IOException(
                        "Invalid replication.state size");
            }

            try (DataInputStream in = new DataInputStream(
                    Files.newInputStream(stateFile))) {

                int version = in.readInt();
                long segment = in.readLong();
                long offset = in.readLong();

                if (version != VERSION
                        || segment <= 0
                        || offset < 0) {
                    throw new IOException(
                            "Invalid replication.state content");
                }

                return Optional.of(
                        new WalPosition(segment, offset));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot load replication.state", e);
        }
    }

    /**
     * Атомарно сохраняет позицию WAL лидера.
     *
     * ВАЖНО: вызывающий код обязан сначала выполнить
     * sync() локального WAL фолловера.
     */
    public synchronized void save(WalPosition position) {
        Objects.requireNonNull(position, "position");

        if (position.segment() <= 0 || position.offset() < 0) {
            throw new IllegalArgumentException(
                    "Invalid WAL position");
        }

        try {
            Files.createDirectories(stateFile.getParent());

            // Создаём временный файл.
            try (DataOutputStream out = new DataOutputStream(
                    Files.newOutputStream(
                            tmpFile, CREATE, WRITE, TRUNCATE_EXISTING))) {

                out.writeInt(VERSION);
                out.writeLong(position.segment());
                out.writeLong(position.offset());
            }

            // Синхронизируем временный файл с диском.
            try (FileChannel channel = FileChannel.open(
                    tmpFile, READ)) {
                channel.force(true);
            }

            // Атомарно заменяем старое состояние новым.
            Files.move(
                    tmpFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );

        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot save replication.state", e);
        }
    }
}
