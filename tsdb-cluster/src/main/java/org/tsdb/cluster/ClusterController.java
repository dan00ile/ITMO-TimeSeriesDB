package org.tsdb.cluster;

import java.util.List;
import java.util.Objects;
import org.tsdb.wal.WalPosition;

/** Доменная часть Promote/GetClusterState. gRPC handlers и AtomicReference<Role> принадлежат C. */
public final class ClusterController {
    private final FollowerLoop follower;
    private final Runnable becomeLeader;
    private boolean promoted;

    /** becomeLeader должен атомарно установить общую роль LEADER. */
    public ClusterController(FollowerLoop follower, Runnable becomeLeader) {
        this.follower = Objects.requireNonNull(follower, "follower");
        this.becomeLeader = Objects.requireNonNull(becomeLeader, "becomeLeader");
    }

    /** Публикуем LEADER только после завершения писателя и сохранения позиции. */
    public synchronized void promote() {
        if (promoted) return;
        follower.close();
        if (follower.failure() != null) {
            throw new IllegalStateException("cannot promote a failed follower", follower.failure());
        }
        becomeLeader.run();
        promoted = true;
    }

    /** Прогресс и connected берутся из согласованного снимка ReplicationHub части A. */
    public static ClusterState clusterState(String leaderId, String leaderAddr, long leaderMaxTs,
                                             List<FollowerProgress> followers) {
        return new ClusterState(leaderId, leaderAddr, followers.stream().map(p -> new FollowerState(
                p.id(), p.addr(), p.applied(), lagMs(leaderMaxTs, p.appliedMaxTs()), p.connected())).toList());
    }

    private static long lagMs(long leaderMaxTs, long appliedMaxTs) {
        if (leaderMaxTs == Long.MIN_VALUE || appliedMaxTs >= leaderMaxTs) return 0;
        if (appliedMaxTs == Long.MIN_VALUE) return Long.MAX_VALUE;
        try {
            return Math.subtractExact(leaderMaxTs, appliedMaxTs);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public record FollowerProgress(String id, String addr, WalPosition applied,
                                   long appliedMaxTs, boolean connected) {}
    public record FollowerState(String id, String addr, WalPosition applied, long lagMs, boolean connected) {}
    public record ClusterState(String leaderId, String leaderAddr, List<FollowerState> followers) {
        public ClusterState {
            followers = List.copyOf(followers);
        }
    }
}
