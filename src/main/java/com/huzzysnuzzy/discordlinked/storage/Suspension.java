package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

public record Suspension(long id, UUID uuid, String reason, String createdBy, Instant createdAt, Instant expiresAt) {
}
