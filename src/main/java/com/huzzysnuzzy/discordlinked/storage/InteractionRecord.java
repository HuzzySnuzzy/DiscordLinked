package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * One /hug, /kiss etc. The UUIDs are null for Discord members without a linked account, and the target fields
 * are null when nobody was picked. {@code source} is the server name, or "discord".
 */
public record InteractionRecord(long id, String action, UUID actorUuid, Long actorDiscord, String actorName,
                                UUID targetUuid, Long targetDiscord, String targetName, String source, String text,
                                Instant createdAt) {
}
