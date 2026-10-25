package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** One row of the audit log. {@code actor} is set for admin actions (who did it). */
public record AuditEntry(long id, Instant timestamp, UUID uuid, String minecraftName, Long discordId,
                         String eventType, String detail, String actor) {
}
