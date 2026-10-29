package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/** An invitation to join a group, waiting for the player to accept or decline it. */
public record GroupInvite(long groupId, String groupName, UUID uuid, String name, String invitedBy, Instant createdAt) {
}
