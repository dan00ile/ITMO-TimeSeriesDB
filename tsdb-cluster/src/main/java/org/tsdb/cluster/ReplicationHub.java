
package org.tsdb.cluster;

import org.tsdb.wal.WalPosition;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Координирует репликацию WAL на стороне лидера.
 */
public final class ReplicationHub {


    private final int maxLagSegments;

    public ReplicationHub() {
        this(8);
    }

    public ReplicationHub(int maxLagSegments) {
        if (maxLagSegments < 0) {
            throw new IllegalArgumentException(
                    "maxLagSegments must be non-negative");
        }

        this.maxLagSegments = maxLagSegments;
    }


    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private WalPosition lastAppended;

    private final Map<String, FollowerProgress> followers =
            new HashMap<>();



    private static final class FollowerProgress {
        boolean connected;
        WalPosition applied;
        WalPosition requestedAfter;
        long appliedMaxTs = Long.MIN_VALUE;
        boolean resyncRequired;
    }



    /**
     * Вызывается после появления новой записи в WAL.
     */

    public void onAppend(WalPosition position) {
        Objects.requireNonNull(position, "position");

        lock.lock();
        try {
            if (lastAppended == null
                    || position.compareTo(lastAppended) > 0) {
                lastAppended = position;
            }

            updateResyncFlags();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }


    /**
     * Ожидает появления записи строго после указанной позиции.
     *
     * @return true, если запись появилась;
     *         false, если истёк таймаут
     */
    public boolean awaitAppendedAfter(
            WalPosition position,
            long timeoutMs
    ) throws InterruptedException {

        Objects.requireNonNull(position, "position");

        if (timeoutMs < 0) {
            throw new IllegalArgumentException(
                    "timeoutMs must be non-negative");
        }

        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        lock.lockInterruptibly();
        try {
            while (lastAppended == null
                    || lastAppended.compareTo(position) <= 0) {

                if (remaining <= 0) {
                    return false;
                }

                remaining = changed.awaitNanos(remaining);
            }

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Регистрирует подключение фолловера.
     */


    /**
     * Подключение фолловера без известной позиции.
     */
    public void connected(String followerId) {
        connected(followerId, null);
    }

    /**
     * Регистрирует фолловера и позицию, с которой
     * он запрашивает продолжение репликации.
     *
     * null означает, что позиция неизвестна.
     */
    public void connected(
            String followerId,
            WalPosition requestedAfter
    ) {
        Objects.requireNonNull(followerId, "followerId");

        lock.lock();
        try {
            FollowerProgress progress =
                    followers.computeIfAbsent(
                            followerId,
                            id -> new FollowerProgress()
                    );

            progress.connected = true;
            progress.requestedAfter = requestedAfter;

            // Не доверяем старым подтверждениям
            // после нового подключения.
            progress.applied = null;
            progress.appliedMaxTs = Long.MIN_VALUE;

            updateResyncFlags();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }



    /**
     * Отмечает отключение фолловера.
     */
    public void disconnected(String followerId) {
        Objects.requireNonNull(followerId, "followerId");

        lock.lock();
        try {
            FollowerProgress progress = followers.get(followerId);

            if (progress != null) {
                progress.connected = false;
            }

            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Принимает подтверждение от фолловера.
     *
     * applied — последняя применённая позиция WAL лидера.
     * appliedMaxTs — максимальный timestamp применённых точек.
     */
    public void onAck(
            String followerId,
            WalPosition applied,
            long appliedMaxTs
    ) {
        Objects.requireNonNull(followerId, "followerId");
        Objects.requireNonNull(applied, "applied");

        lock.lock();
        try {
            FollowerProgress progress = followers.get(followerId);

            if (progress == null
                    || !progress.connected
                    || progress.resyncRequired) {
                return;
            }

            if (progress.applied == null
                    || applied.compareTo(progress.applied) > 0) {

                progress.applied = applied;
                progress.appliedMaxTs = appliedMaxTs;

                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ожидает, пока необходимое количество подключённых
     * фолловеров подтвердит указанную позицию или более новую.
     */
    public boolean awaitAcks(
            WalPosition position,
            int minAcks,
            long timeoutMs
    ) throws InterruptedException {

        Objects.requireNonNull(position, "position");

        if (minAcks < 0 || timeoutMs < 0) {
            throw new IllegalArgumentException(
                    "minAcks and timeoutMs must be non-negative");
        }

        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        lock.lockInterruptibly();
        try {
            while (true) {
                int ackCount = 0;

                for (FollowerProgress progress : followers.values()) {
                    if (progress.connected
                            && !progress.resyncRequired
                            && progress.applied != null
                            && progress.applied.compareTo(position) >= 0) {
                        ackCount++;
                    }
                }

                if (ackCount >= minAcks) {
                    return true;
                }

                if (remaining <= 0) {
                    return false;
                }

                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Возвращает минимальную подтверждённую позицию
     * среди подключённых фолловеров.
     */
    public Optional<WalPosition> minAppliedOfConnected() {
        lock.lock();
        try {
            WalPosition minimum = null;

            for (FollowerProgress progress : followers.values()) {
                if (!progress.connected) {
                    continue;
                }

                if (progress.applied == null) {
                    return Optional.empty();
                }

                if (minimum == null
                        || progress.applied.compareTo(minimum) < 0) {
                    minimum = progress.applied;
                }
            }

            return Optional.ofNullable(minimum);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Возвращает границу хранения WAL для репликации.
     *
     * Если нет подключённых фолловеров — ограничений нет.
     * Если хотя бы один не прислал Ack — защищаем весь WAL.
     */

    public Optional<WalPosition> retentionFloor() {
        lock.lock();
        try {
            WalPosition minimum = null;
            boolean hasEligibleFollower = false;

            for (FollowerProgress progress : followers.values()) {
                if (!progress.connected || progress.resyncRequired) {
                    continue;
                }

                hasEligibleFollower = true;

                if (progress.applied == null) {
                    return Optional.of(new WalPosition(0, 0));
                }

                if (minimum == null
                        || progress.applied.compareTo(minimum) < 0) {
                    minimum = progress.applied;
                }
            }

            if (!hasEligibleFollower) {
                return Optional.empty();
            }

            return Optional.of(minimum);
        } finally {
            lock.unlock();
        }
    }



    /**
     * Проверяет отставание подключённых фолловеров.
     * Вызывать только при удерживаемом lock.
     */

    private void updateResyncFlags() {
        if (lastAppended == null) {
            return;
        }

        for (FollowerProgress progress : followers.values()) {
            if (!progress.connected || progress.resyncRequired) {
                continue;
            }

            WalPosition followerPosition =
                    progress.applied != null
                            ? progress.applied
                            : progress.requestedAfter;

            // Пока нет известной позиции, нельзя
            // достоверно рассчитать отставание.
            if (followerPosition == null) {
                continue;
            }

            long lag = lastAppended.segment()
                    - followerPosition.segment();

            if (lag > maxLagSegments) {
                progress.resyncRequired = true;
            }
        }
    }

    /**
     * Нужна ли фолловеру полная синхронизация.
     */
    public boolean requiresFullResync(String followerId) {
        Objects.requireNonNull(followerId, "followerId");

        lock.lock();
        try {
            FollowerProgress progress = followers.get(followerId);

            return progress != null && progress.resyncRequired;
        } finally {
            lock.unlock();
        }
    }

}
