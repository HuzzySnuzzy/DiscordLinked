package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** A cosmetic a player has: what they paid (0 if given) and since when it's in use ({@code equippedAt}, or null). */
public record OwnedCosmetic(UUID uuid, long cosmeticId, long paid, Instant acquiredAt, Instant equippedAt) {

    public boolean equipped() {
        return equippedAt != null;
    }
}
