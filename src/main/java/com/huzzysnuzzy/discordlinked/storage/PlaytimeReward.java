package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/** Credits a player just got for active playtime, and their balance after. */
public record PlaytimeReward(UUID uuid, long amount, long balance) {
}
