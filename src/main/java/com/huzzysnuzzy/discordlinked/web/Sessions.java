package com.huzzysnuzzy.discordlinked.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory login sessions and pending OAuth states. Everyone is logged out when the proxy restarts. */
final class Sessions {

    static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> states = new ConcurrentHashMap<>();

    String newState() {
        purge();
        String state = token();
        states.put(state, Instant.now().plus(STATE_LIFETIME));
        return state;
    }

    boolean consumeState(String state) {
        Instant expires = state == null ? null : states.remove(state);
        return expires != null && expires.isAfter(Instant.now());
    }

    Session create(DiscordOAuth.DiscordUser user) {
        purge();
        Session session = new Session(token(), user, token(), Instant.now().plus(SESSION_LIFETIME));
        sessions.put(session.token, session);
        return session;
    }

    Session get(String token) {
        if (token == null) {
            return null;
        }
        Session session = sessions.get(token);
        if (session != null && session.expiresAt.isBefore(Instant.now())) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private void purge() {
        Instant now = Instant.now();
        sessions.values().removeIf(s -> s.expiresAt.isBefore(now));
        states.values().removeIf(expires -> expires.isBefore(now));
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class Session {
        final String token;
        final DiscordOAuth.DiscordUser user;
        final String csrfToken;
        final Instant expiresAt;
        volatile Instant lastRoleCheck = Instant.now();

        Session(String token, DiscordOAuth.DiscordUser user, String csrfToken, Instant expiresAt) {
            this.token = token;
            this.user = user;
            this.csrfToken = csrfToken;
            this.expiresAt = expiresAt;
        }
    }
}
