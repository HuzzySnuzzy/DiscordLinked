package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

/** An advancement a player made, the first time on any server. {@code frame} is TASK, GOAL or CHALLENGE. */
public record EarnedAdvancement(String key, String title, String frame, String server, long credits, Instant earnedAt) {
}
