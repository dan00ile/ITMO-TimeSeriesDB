
package org.tsdb.cluster;

import org.junit.jupiter.api.Test;
import org.tsdb.wal.WalPosition;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;


class ReplicationHubTest {

    @Test
    void returnsTrueWhenNewPositionExists() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.onAppend(new WalPosition(1, 200));

        assertTrue(hub.awaitAppendedAfter(
                new WalPosition(1, 100), 100));
    }

    @Test
    void returnsFalseOnTimeout() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.onAppend(new WalPosition(1, 100));

        assertFalse(hub.awaitAppendedAfter(
                new WalPosition(1, 100), 50));
    }

    @Test
    void wakesWaitingThreadAfterAppend() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> result = executor.submit(
                    () -> hub.awaitAppendedAfter(
                            new WalPosition(1, 100), 2000));

            hub.onAppend(new WalPosition(1, 200));

            assertTrue(result.get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void ackFromConnectedFollowerIsAccepted() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");

        WalPosition position = new WalPosition(1, 200);

        hub.onAck("n2", position, 1000);

        assertTrue(hub.awaitAcks(position, 1, 100));
    }

    @Test
    void insufficientAcksCauseTimeout() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");
        hub.connected("n3");

        WalPosition position = new WalPosition(1, 200);

        hub.onAck("n2", position, 1000);

        // Требуем 2 подтверждения, но получили только 1.
        assertFalse(hub.awaitAcks(position, 2, 50));
    }

    @Test
    void laterAckAlsoConfirmsEarlierPosition() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");

        hub.onAck("n2", new WalPosition(1, 300), 3000);

        assertTrue(hub.awaitAcks(
                new WalPosition(1, 200), 1, 100));
    }

    @Test
    void disconnectedFollowerDoesNotCount() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");

        WalPosition position = new WalPosition(1, 200);
        hub.onAck("n2", position, 1000);

        hub.disconnected("n2");

        assertFalse(hub.awaitAcks(position, 1, 50));
    }

    @Test
    void olderAckDoesNotMoveProgressBackward() throws Exception {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");

        WalPosition newer = new WalPosition(1, 300);
        WalPosition older = new WalPosition(1, 200);

        hub.onAck("n2", newer, 3000);
        hub.onAck("n2", older, 2000);

        assertTrue(hub.awaitAcks(newer, 1, 100));
        assertEquals(newer,
                hub.minAppliedOfConnected().orElseThrow());
    }

    @Test
    void minimumAppliedPositionIsReturned() {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");
        hub.connected("n3");

        hub.onAck("n2", new WalPosition(1, 900), 9000);
        hub.onAck("n3", new WalPosition(1, 600), 6000);

        assertEquals(
                new WalPosition(1, 600),
                hub.minAppliedOfConnected().orElseThrow()
        );
    }

    @Test
    void unknownConnectedProgressReturnsEmpty() {
        ReplicationHub hub = new ReplicationHub();

        hub.connected("n2");

        assertTrue(hub.minAppliedOfConnected().isEmpty());
    }

    @Test
    void retentionFloorProtectsUnackedFollower() {
        ReplicationHub hub = new ReplicationHub();

        assertTrue(hub.retentionFloor().isEmpty());

        hub.connected("n2");

        assertEquals(
                new WalPosition(0, 0),
                hub.retentionFloor().orElseThrow()
        );

        hub.onAck("n2", new WalPosition(2, 100), 1000);

        assertEquals(
                new WalPosition(2, 100),
                hub.retentionFloor().orElseThrow()
        );
    }


    @Test
    void followerWithinLagLimitDoesNotRequireResync() {
        ReplicationHub hub = new ReplicationHub(3);

        hub.onAppend(new WalPosition(5, 100));
        hub.connected("n2");

        hub.onAck("n2", new WalPosition(5, 100), 1000);

        // Отставание составляет 3 сегмента.
        hub.onAppend(new WalPosition(8, 100));

        assertFalse(hub.requiresFullResync("n2"));

        assertEquals(
                new WalPosition(5, 100),
                hub.retentionFloor().orElseThrow()
        );
    }

    @Test
    void followerBeyondLagLimitRequiresResync() {
        ReplicationHub hub = new ReplicationHub(3);

        hub.onAppend(new WalPosition(5, 100));
        hub.connected("n2");

        hub.onAck("n2", new WalPosition(5, 100), 1000);

        // Отставание составляет 4 сегмента.
        hub.onAppend(new WalPosition(9, 100));

        assertTrue(hub.requiresFullResync("n2"));

        // Фолловер больше не удерживает старый WAL.
        assertTrue(hub.retentionFloor().isEmpty());
    }

    @Test
    void laggingFollowerDoesNotBlockHealthyFollower() {
        ReplicationHub hub = new ReplicationHub(3);

        hub.onAppend(new WalPosition(5, 100));

        hub.connected("n2");
        hub.connected("n3");

        hub.onAck("n2", new WalPosition(5, 100), 1000);
        hub.onAck("n3", new WalPosition(7, 100), 2000);

        hub.onAppend(new WalPosition(9, 100));

        assertTrue(hub.requiresFullResync("n2"));
        assertFalse(hub.requiresFullResync("n3"));

        // Retention учитывает только здоровый n3.
        assertEquals(
                new WalPosition(7, 100),
                hub.retentionFloor().orElseThrow()
        );
    }

    @Test
    void resyncRequiredFollowerCannotSatisfyAck() throws Exception {
        ReplicationHub hub = new ReplicationHub(2);

        hub.onAppend(new WalPosition(1, 100));
        hub.connected("n2");

        hub.onAck("n2", new WalPosition(1, 100), 1000);

        hub.onAppend(new WalPosition(4, 100));

        assertTrue(hub.requiresFullResync("n2"));

        // Даже новое подтверждение не снимает resyncRequired.
        hub.onAck("n2", new WalPosition(4, 100), 4000);

        assertFalse(hub.awaitAcks(
                new WalPosition(4, 100), 1, 50
        ));
    }


    @Test
    void usesActualFollowerPositionOnConnect() {
        ReplicationHub hub = new ReplicationHub(3);

        hub.onAppend(new WalPosition(10, 100));

        // Фолловер подключился, но имеет только сегмент 5.
        hub.connected("n2", new WalPosition(5, 100));

        assertTrue(hub.requiresFullResync("n2"));
    }

    @Test
    void recentFollowerCanContinueReplication() {
        ReplicationHub hub = new ReplicationHub(3);

        hub.onAppend(new WalPosition(10, 100));

        hub.connected("n2", new WalPosition(9, 100));

        assertFalse(hub.requiresFullResync("n2"));

        hub.onAck("n2", new WalPosition(10, 100), 1000);

        assertEquals(
                new WalPosition(10, 100),
                hub.retentionFloor().orElseThrow()
        );
    }

    @Test
    void reconnectDoesNotReuseOldAck() throws Exception {
        ReplicationHub hub = new ReplicationHub(8);

        hub.onAppend(new WalPosition(5, 100));

        hub.connected("n2");
        hub.onAck("n2", new WalPosition(5, 100), 1000);

        assertTrue(hub.awaitAcks(
                new WalPosition(5, 100), 1, 0));

        hub.disconnected("n2");

        // Фолловер восстановился с более ранней позиции.
        hub.connected("n2", new WalPosition(4, 100));

        assertFalse(hub.awaitAcks(
                new WalPosition(5, 100), 1, 0));

        assertEquals(
                new WalPosition(0, 0),
                hub.retentionFloor().orElseThrow()
        );
    }



}
