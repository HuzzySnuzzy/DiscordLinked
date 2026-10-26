package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** Anyone who has played: joined through the proxy, or found in a Minecraft server's player data. */
public record KnownPlayer(UUID uuid, String name, Instant firstPlayed, Instant lastPlayed) {
}
