package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

public record LinkedAccount(UUID uuid, String minecraftName, long discordId, Instant linkedAt) {
}
