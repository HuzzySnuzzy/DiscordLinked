package com.huzzysnuzzy.discordlinked.storage;

import java.time.Instant;

public record PlayerIp(String ip, Instant firstSeen, Instant lastSeen, int times) {
}
