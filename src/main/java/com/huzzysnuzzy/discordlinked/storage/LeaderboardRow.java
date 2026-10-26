package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/** A player's community stats; {@code given} and {@code received} count interactions. */
public record LeaderboardRow(UUID uuid, String name, long credits, long earned, long activeSeconds, long advancements,
                             long given, long received) {
}
