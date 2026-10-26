package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

/**
 * One change to a player's credits. {@code kind} is PLAYTIME (one row per player per day), ADVANCEMENT or ADMIN;
 * {@code actor} is set for ADMIN. {@code balance} is what they had right after.
 */
public record CreditTransaction(long id, long amount, long balance, String kind, String detail, String actor,
                                Instant createdAt) {
}
