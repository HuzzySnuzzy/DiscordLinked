package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/**
 * A player's credits, microcredits and playtime. {@code earned} and {@code microEarned} are everything ever added,
 * before anything was taken away.
 */
public record CreditAccount(UUID uuid, String name, long credits, long earned, long activeSeconds, long afkSeconds,
                            long micro, long microEarned) {
}
