package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** {@code liftedAt}, {@code liftedBy} and {@code liftReason} are null unless it was ended early. */
public record Suspension(long id, UUID uuid, String reason, String createdBy, Instant createdAt, Instant expiresAt,
                         Instant liftedAt, String liftedBy, String liftReason) {

    public boolean isActive() {
        return liftedAt == null && expiresAt.isAfter(Instant.now());
    }
}
