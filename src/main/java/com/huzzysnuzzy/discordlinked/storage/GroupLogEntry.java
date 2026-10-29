package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * One change to a group: a sale or exchange by a member, someone joining (and the microcredits they brought),
 * leaving (with their share), a rename and so on. {@code amount} is what it did to the balance, {@code balance}
 * what was left.
 */
public record GroupLogEntry(long id, Instant time, UUID uuid, String name, String kind, long amount, long balance,
                            String detail) {
}
