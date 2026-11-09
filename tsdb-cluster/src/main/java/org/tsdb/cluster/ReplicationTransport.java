package org.tsdb.cluster;

import java.util.Iterator;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;

/** Клиентский транспорт части C; оба RPC создаются внутри текущего gRPC Context. */
@FunctionalInterface
public interface ReplicationTransport {
    /** after — последняя применённая позиция лидера; null передаётся как отсутствующее поле proto. */
    Session connect(String followerId, WalPosition after);

    /** Отмена текущего Context обязана прерывать connect и блокирующий entries().hasNext(). */
    interface Session extends AutoCloseable {
        Iterator<WalEntry> entries();

        /**
         * Единственный отправитель — задача tsdb-follower-ack. Асинхронную ошибку Ack RPC
         * сохранить и бросить как StatusRuntimeException при следующем ack: loop пересоздаст оба RPC.
         */
        void ack(ReplicationProgress progress);

        /** Закрывает Ack stream. FollowerLoop исключает параллельный вызов ack и close. */
        @Override void close();
    }
}
