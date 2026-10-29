package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A group of players who share their microcredits: everything members earn by selling goes into {@code balance},
 * and any member can spend it. {@code leader} names it, invites and removes members and sees the log.
 */
public record PlayerGroup(long id, String name, UUID leader, long balance, Instant createdAt, List<Member> members) {

    /** {@code microEarned}: everything they ever earned by selling (also before joining). */
    public record Member(UUID uuid, String name, Instant joinedAt, long microEarned) {
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    /** Microcredits the members earned by selling, together. */
    public long microEarned() {
        return members.stream().mapToLong(Member::microEarned).sum();
    }
}
