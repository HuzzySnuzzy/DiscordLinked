package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * A player's home (/sethome): a spot on one of the backend servers they can teleport back to with /home. Players in
 * the same group can use each other's homes, but only the owner changes or removes them.
 */
public record Home(UUID owner, String ownerName, String name, String server, String world, double x, double y,
                   double z, float yaw, float pitch, Instant createdAt, Instant updatedAt) {
}
