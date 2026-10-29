package com.huzzysnuzzy.discordlinked.storage;

import java.util.UUID;

/**
 * One place on the microcredit leaderboard: a player on their own, or a group as one (then {@code group} is set and
 * {@code uuid} is its leader).
 */
public record MicroBoardRow(UUID uuid, String name, long value, PlayerGroup group) {
}
