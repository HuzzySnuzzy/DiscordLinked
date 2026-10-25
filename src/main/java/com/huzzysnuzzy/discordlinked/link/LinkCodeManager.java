package com.huzzysnuzzy.discordlinked.link;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived codes handed to unlinked players in their kick message.
 * The player redeems the code with /link in Discord.
 */
public final class LinkCodeManager {

    // No 0/O/1/I so codes are easy to read off the screen.
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final Duration lifetime;
    private final Map<String, PendingLink> byCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();

    public LinkCodeManager(Duration lifetime) {
        this.lifetime = lifetime;
    }

    /** Returns the player's existing unexpired code, or issues a new one. */
    public synchronized String codeFor(UUID uuid, String username) {
        purgeExpired();
        String existing = byPlayer.get(uuid);
        if (existing != null) {
            return existing;
        }
        String code;
        do {
            code = randomCode();
        } while (byCode.containsKey(code));
        byCode.put(code, new PendingLink(uuid, username, Instant.now().plus(lifetime)));
        byPlayer.put(uuid, code);
        return code;
    }

    public synchronized Optional<PendingLink> find(String code) {
        purgeExpired();
        return Optional.ofNullable(byCode.get(code.trim().toUpperCase()));
    }

    public synchronized void consume(String code) {
        PendingLink link = byCode.remove(code.trim().toUpperCase());
        if (link != null) {
            byPlayer.remove(link.uuid());
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        byCode.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt().isBefore(now)) {
                byPlayer.remove(entry.getValue().uuid());
                return true;
            }
            return false;
        });
    }

    private String randomCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }

    public record PendingLink(UUID uuid, String username, Instant expiresAt) {
    }
}
