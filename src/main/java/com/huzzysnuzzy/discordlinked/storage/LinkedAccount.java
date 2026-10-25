package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code discordName} and {@code discordAvatar} are cached profile details and may be null.
 * {@code lastSeen} is when they last joined or left; null if never.
 */
public record LinkedAccount(UUID uuid, String minecraftName, long discordId, Instant linkedAt,
                            String discordName, String discordAvatar, Instant lastSeen) {
}
