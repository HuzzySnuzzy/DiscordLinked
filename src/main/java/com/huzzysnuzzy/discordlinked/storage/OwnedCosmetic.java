package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * A cosmetic a player has: what they paid (0 if given), since when it's in use ({@code equippedAt}, or null) and,
 * for trails and auras, the look they picked ({@code style}, or null for the item's own).
 */
public record OwnedCosmetic(UUID uuid, long cosmeticId, long paid, Instant acquiredAt, Instant equippedAt,
                            String style) {

    public boolean equipped() {
        return equippedAt != null;
    }
}
