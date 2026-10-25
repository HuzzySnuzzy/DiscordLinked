package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

public record AuditEntry(Instant timestamp, String minecraftName, String eventType, String detail) {
}
