package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the audit log. {@code actor} is set for admin actions (who did it), {@code server} is the backend
 * server it happened on (null when unknown, and on entries from before 0.22.0).
 */
public record AuditEntry(long id, Instant timestamp, UUID uuid, String minecraftName, Long discordId,
                         String eventType, String detail, String actor, String server) {
}
