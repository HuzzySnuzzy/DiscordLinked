package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/** A restore waiting for the player to come online. */
public record PendingRestore(long id, UUID uuid, long snapshotId, boolean includeEnderChest, String actor, String reason) {
}
