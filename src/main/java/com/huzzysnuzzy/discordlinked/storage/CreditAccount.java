package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/** A player's credits and playtime. {@code earned} is everything ever added, before anything was taken away. */
public record CreditAccount(UUID uuid, String name, long credits, long earned, long activeSeconds, long afkSeconds) {
}
