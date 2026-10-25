package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

public record PlayerNote(long id, String note, String author, Instant createdAt) {
}
