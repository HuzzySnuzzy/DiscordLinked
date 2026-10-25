package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** A saved inventory. {@code display} (JSON for the website) and {@code data} (exact items) may be null in listings. */
public record InventorySnapshotRecord(long id, UUID uuid, String minecraftName, String server, String reason,
                                      String detail, Instant createdAt, int stacks, String display, byte[] data) {
}
