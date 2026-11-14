package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * A cosmetic a player has: what they paid (0 if given), since when it's in use ({@code equippedAt}, or null) and the
 * look they picked ({@code style}, or null for the item's own). A particle is used as a trail ({@code trailAt}, with
 * {@code trailStyle}) and/or an aura ({@code auraAt}, {@code auraStyle}) instead; both cost nothing more.
 */
public record OwnedCosmetic(UUID uuid, long cosmeticId, long paid, Instant acquiredAt, Instant equippedAt,
                            String style, Instant trailAt, String trailStyle, Instant auraAt, String auraStyle) {
    public boolean equipped() {
        return equippedAt != null;
    }

    public boolean trail() {
        return trailAt != null;
    }

    public boolean aura() {
        return auraAt != null;
    }
}
