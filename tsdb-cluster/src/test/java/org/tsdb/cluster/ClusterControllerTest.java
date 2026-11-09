package org.tsdb.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tsdb.db.DbOptions;
import org.tsdb.db.LocalTimeSeriesDB;
import org.tsdb.model.Labels;
import org.tsdb.wal.WalEntry;
import org.tsdb.wal.WalPosition;
import org.tsdb.wal.WalRecord;

class ClusterControllerTest {
    @TempDir Path dir;

    @Test
    void promoteStopsReplicationBeforePublishingLeaderRoleAndKeepsIds() throws Exception {
        var transport = new FollowerLoopTest.TestTransport(List.of(new WalEntry(new WalPosition(7, 10),
                new WalRecord.SeriesCreated(91, Labels.of("old_metric")))), false);
        AtomicBoolean leader = new AtomicBoolean();
        AtomicInteger promotions = new AtomicInteger();
        try (LocalTimeSeriesDB db = LocalTimeSeriesDB.open(DbOptions.builder(dir)
                .compactionIntervalMs(0).walSyncIntervalMs(0).build());
             FollowerLoop loop = new FollowerLoop("n2", db, transport, new FollowerLoopTest.MemoryCheckpoint())) {
            ClusterController controller = new ClusterController(loop, () -> {
                assertTrue(transport.closed);
                assertFalse(loop.connected());
                leader.set(true);
                promotions.incrementAndGet();
            });
            loop.start();
            FollowerLoopTest.await(() -> loop.progress().applied() != null);
            controller.promote();
            controller.promote();
            assertTrue(leader.get());
            assertEquals(1, promotions.get());
            db.append(Labels.of("new_metric"), 1000, 0.5);
            try (var records = db.wal().readFrom(new WalPosition(1, 0))) {
                assertTrue(records.anyMatch(e -> e.record() instanceof WalRecord.SeriesCreated c && c.id() == 92));
            }
        }
    }

    @Test
    void clusterStateUsesAppliedTimeAndPreservesConnectionStatus() {
        var state = ClusterController.clusterState("n1", "n1:2222", 5000, List.of(
                new ClusterController.FollowerProgress("n2", "n2:2222", new WalPosition(7, 10), 3500, true),
                new ClusterController.FollowerProgress("n3", "n3:2222", null, Long.MIN_VALUE, false),
                new ClusterController.FollowerProgress("n4", "n4:2222", new WalPosition(7, 30), 6000, true)));
        assertEquals("n1", state.leaderId());
        assertEquals("n1:2222", state.leaderAddr());
        assertEquals(1500, state.followers().get(0).lagMs());
        assertTrue(state.followers().get(0).connected());
        assertEquals(Long.MAX_VALUE, state.followers().get(1).lagMs());
        assertFalse(state.followers().get(1).connected());
        assertEquals(0, state.followers().get(2).lagMs());
    }

    @Test
    void failedCheckpointPreventsRoleChange() {
        AtomicBoolean leader = new AtomicBoolean();
        FollowerCheckpoint checkpoint = new FollowerCheckpoint() {
            @Override public ReplicationProgress load() { return ReplicationProgress.empty(); }
            @Override public void save(ReplicationProgress p) { throw new IllegalStateException("disk failure"); }
        };
        var transport = new FollowerLoopTest.TestTransport(List.of(new WalEntry(new WalPosition(7, 10),
                new WalRecord.SeriesCreated(91, Labels.of("old_metric")))), false);
        try (LocalTimeSeriesDB db = LocalTimeSeriesDB.open(DbOptions.builder(dir)
                .compactionIntervalMs(0).walSyncIntervalMs(0).build())) {
            FollowerLoop loop = new FollowerLoop("n2", db, transport, checkpoint);
            ClusterController controller = new ClusterController(loop, () -> leader.set(true));
            loop.start();
            assertDoesNotThrow(() -> FollowerLoopTest.await(() -> loop.progress().applied() != null));
            assertThrows(IllegalStateException.class, controller::promote);
            assertFalse(leader.get());
        }
    }

    @Test
    void lagHandlesEmptyLeaderAndTimestampOverflow() {
        var empty = ClusterController.clusterState("n1", "n1:2222", Long.MIN_VALUE, List.of(
                new ClusterController.FollowerProgress("n2", "", null, Long.MIN_VALUE, true)));
        assertEquals(0, empty.followers().getFirst().lagMs());
        var overflow = ClusterController.clusterState("n1", "n1:2222", Long.MAX_VALUE, List.of(
                new ClusterController.FollowerProgress("n2", "", null, -100, true)));
        assertEquals(Long.MAX_VALUE, overflow.followers().getFirst().lagMs());
    }
}
