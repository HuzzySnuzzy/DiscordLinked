package com.huzzysnuzzy.discordlinked.web;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.AuditEntry;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.Suspension;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.exceptions.ParsingException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The admin website. Admins log in with Discord and must hold {@code admin-role-id};
 * the role is re-checked every few minutes while they stay logged in.
 */
public final class WebServer {

    private static final Duration ROLE_RECHECK = Duration.ofMinutes(5);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int LOG_PAGE_SIZE = 100;
    private static final String SESSION_COOKIE = "dl_session";
    private static final String STATE_COOKIE = "dl_state";

    private static final Map<String, Set<String>> LOG_FILTERS = Map.of(
            "admin", Set.of(EventTypes.LINK, EventTypes.UNLINK, EventTypes.SUSPEND, EventTypes.UNSUSPEND, EventTypes.WEB_LOGIN),
            "links", Set.of(EventTypes.LINK, EventTypes.UNLINK),
            "suspensions", Set.of(EventTypes.SUSPEND, EventTypes.UNSUSPEND, EventTypes.DENIED_SUSPENDED),
            "sessions", Set.of(EventTypes.JOIN, EventTypes.LEAVE, EventTypes.SERVER_SWITCH),
            "chat", Set.of(EventTypes.CHAT),
            "commands", Set.of(EventTypes.COMMAND),
            "denied", Set.of(EventTypes.DENIED_UNLINKED, EventTypes.DENIED_SUSPENDED),
            "web", Set.of(EventTypes.WEB_LOGIN));

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml");

    private final DiscordLinked plugin;
    private final Sessions sessions = new Sessions();
    private final Map<String, byte[]> files = new HashMap<>();
    private HttpServer server;
    private ExecutorService executor;
    private DiscordOAuth oauth;
    private boolean secureCookies;

    public WebServer(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    public void start() throws IOException {
        PluginConfig config = plugin.config();
        if (!config.botConfigured() || config.oauthClientSecret().isEmpty() || config.adminRoleId() == 0L) {
            plugin.logger().warn("Website not started: it needs bot-token, guild-id, discord-client-secret and "
                    + "admin-role-id set in config.properties.");
            return;
        }
        for (String name : List.of("index.html", "login.html", "message.html", "app.js", "style.css", "favicon.svg")) {
            try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
                if (in == null) {
                    throw new IOException("web/" + name + " missing from plugin jar");
                }
                files.put(name, in.readAllBytes());
            }
        }
        oauth = new DiscordOAuth(config.oauthClientSecret(), config.webPublicUrl() + "/callback");
        secureCookies = config.webPublicUrl().startsWith("https://");

        server = HttpServer.create(new InetSocketAddress(config.webBindAddress(), config.webPort()), 0);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread thread = new Thread(r, "DiscordLinked-Web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        plugin.logger().info("Admin website running on port {} — open {}", config.webPort(), config.webPublicUrl());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    // ---- Routing ----

    private void handle(HttpExchange ex) {
        try (ex) {
            addSecurityHeaders(ex);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                handleApi(ex, method, path);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(ex, 405, "Method not allowed");
                return;
            }
            switch (path) {
                case "/" -> serveFile(ex, currentSession(ex) != null ? "index.html" : "login.html");
                case "/login" -> startLogin(ex);
                case "/callback" -> finishLogin(ex);
                case "/logout" -> logout(ex);
                case "/app.js", "/style.css", "/favicon.svg" -> serveFile(ex, path.substring(1));
                default -> sendMessagePage(ex, 404, "Not found", "That page doesn't exist.");
            }
        } catch (Exception e) {
            plugin.logger().error("Website error on {}", ex.getRequestURI().getPath(), e);
            try {
                sendText(ex, 500, "Internal error");
            } catch (IOException ignored) {
                // response already started
            }
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException, SQLException {
        Sessions.Session session = currentSession(ex);
        if (session == null) {
            sendJson(ex, 401, error("Not logged in"));
            return;
        }
        if (method.equals("POST")) {
            String csrf = ex.getRequestHeaders().getFirst("X-CSRF-Token");
            if (csrf == null || !csrf.equals(session.csrfToken)) {
                sendJson(ex, 403, error("Invalid CSRF token, reload the page"));
                return;
            }
        }
        switch (method + " " + path) {
            case "GET /api/me" -> sendJson(ex, 200, DataObject.empty()
                    .put("id", String.valueOf(session.user.id()))
                    .put("name", session.user.name())
                    .put("avatar", session.user.avatarUrl())
                    .put("csrf", session.csrfToken));
            case "GET /api/linked" -> sendJson(ex, 200, linkedList());
            case "GET /api/logs" -> sendJson(ex, 200, logs(query(ex)));
            case "POST /api/unlink", "POST /api/suspend", "POST /api/unsuspend" -> handleAction(ex, path, session);
            default -> sendJson(ex, 404, error("Unknown endpoint"));
        }
    }

    // ---- Login ----

    private void startLogin(HttpExchange ex) throws IOException {
        if (!plugin.bot().isReady()) {
            sendMessagePage(ex, 503, "Bot offline", "The Discord bot isn't connected yet. Try again in a moment.");
            return;
        }
        String clientId = plugin.config().oauthClientId().isEmpty()
                ? plugin.bot().applicationId() : plugin.config().oauthClientId();
        String state = sessions.newState();
        ex.getResponseHeaders().add("Set-Cookie", cookie(STATE_COOKIE, state, 600));
        redirect(ex, oauth.authorizeUrl(clientId, state));
    }

    private void finishLogin(HttpExchange ex) throws IOException, InterruptedException {
        Map<String, String> query = query(ex);
        String state = query.get("state");
        ex.getResponseHeaders().add("Set-Cookie", cookie(STATE_COOKIE, "", 0));
        if (state == null || !state.equals(cookies(ex).get(STATE_COOKIE)) || !sessions.consumeState(state)) {
            sendMessagePage(ex, 400, "Login expired", "Your login attempt expired or was invalid. Please try again.");
            return;
        }
        String code = query.get("code");
        if (code == null) {
            sendMessagePage(ex, 400, "Login cancelled", "Discord didn't log you in. Please try again.");
            return;
        }
        if (!plugin.bot().isReady()) {
            sendMessagePage(ex, 503, "Bot offline", "The Discord bot isn't connected yet. Try again in a moment.");
            return;
        }
        String clientId = plugin.config().oauthClientId().isEmpty()
                ? plugin.bot().applicationId() : plugin.config().oauthClientId();
        DiscordOAuth.DiscordUser user;
        try {
            user = oauth.identify(clientId, code);
        } catch (IOException e) {
            plugin.logger().warn("Discord login failed: {}", e.getMessage());
            sendMessagePage(ex, 502, "Login failed", "Discord rejected the login. Check discord-client-secret and "
                    + "that the redirect URL is added in the Developer Portal.");
            return;
        }
        if (!plugin.bot().hasAdminRole(user.id())) {
            plugin.audit().log(null, null, user.id(), EventTypes.WEB_LOGIN, "denied: missing admin role", user.actor());
            sendMessagePage(ex, 403, "No access", "Your Discord account doesn't have the admin role for this panel.");
            return;
        }
        Sessions.Session session = sessions.create(user);
        plugin.audit().log(null, null, user.id(), EventTypes.WEB_LOGIN, "logged in", user.actor());
        ex.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, session.token,
                Sessions.SESSION_LIFETIME.toSeconds()));
        redirect(ex, "/");
    }

    private void logout(HttpExchange ex) throws IOException {
        sessions.invalidate(cookies(ex).get(SESSION_COOKIE));
        ex.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, "", 0));
        redirect(ex, "/");
    }

    /** The logged-in session, or null. Drops the session if the user lost the admin role. */
    private Sessions.Session currentSession(HttpExchange ex) {
        Sessions.Session session = sessions.get(cookies(ex).get(SESSION_COOKIE));
        if (session == null) {
            return null;
        }
        if (Duration.between(session.lastRoleCheck, Instant.now()).compareTo(ROLE_RECHECK) > 0) {
            if (!plugin.bot().isReady() || !plugin.bot().hasAdminRole(session.user.id())) {
                sessions.invalidate(session.token);
                return null;
            }
            session.lastRoleCheck = Instant.now();
        }
        return session;
    }

    // ---- API ----

    private DataArray linkedList() throws SQLException {
        Map<UUID, Suspension> suspensions = plugin.database().activeSuspensions();
        DataArray result = DataArray.empty();
        for (LinkedAccount account : plugin.database().allLinked()) {
            if (account.discordName() == null) {
                plugin.bot().refreshProfile(account.discordId());
            }
            Optional<Player> online = plugin.proxy().getPlayer(account.uuid());
            DataObject row = DataObject.empty()
                    .put("uuid", account.uuid().toString())
                    .put("minecraftName", account.minecraftName())
                    .put("discordId", String.valueOf(account.discordId()))
                    .put("discordName", account.discordName() == null ? "Unknown user" : account.discordName())
                    .put("discordAvatar", account.discordAvatar() == null
                            ? DiscordOAuth.avatarUrl(account.discordId(), null) : account.discordAvatar())
                    .put("linkedAt", account.linkedAt().toEpochMilli())
                    .put("online", online.isPresent())
                    .put("server", online.flatMap(Player::getCurrentServer)
                            .map(s -> s.getServerInfo().getName()).orElse(""));
            Suspension suspension = suspensions.get(account.uuid());
            if (suspension == null) {
                row.putNull("suspension");
            } else {
                row.put("suspension", DataObject.empty()
                        .put("reason", suspension.reason())
                        .put("createdBy", suspension.createdBy())
                        .put("createdAt", suspension.createdAt().toEpochMilli())
                        .put("expiresAt", suspension.expiresAt().toEpochMilli()));
            }
            result.add(row);
        }
        return result;
    }

    private DataObject logs(Map<String, String> query) throws SQLException {
        Set<String> types = LOG_FILTERS.getOrDefault(query.getOrDefault("filter", "all"), Set.of());
        String search = query.getOrDefault("q", "").trim();
        long before = parseLong(query.get("before"));
        List<AuditEntry> entries = plugin.database().searchEvents(types, search, before, LOG_PAGE_SIZE);
        DataArray rows = DataArray.empty();
        for (AuditEntry entry : entries) {
            rows.add(DataObject.empty()
                    .put("id", entry.id())
                    .put("time", entry.timestamp().toEpochMilli())
                    .put("type", entry.eventType())
                    .put("player", entry.minecraftName() == null ? "" : entry.minecraftName())
                    .put("uuid", entry.uuid() == null ? "" : entry.uuid().toString())
                    .put("discordId", entry.discordId() == null ? "" : String.valueOf(entry.discordId()))
                    .put("actor", entry.actor() == null ? "" : entry.actor())
                    .put("detail", entry.detail() == null ? "" : entry.detail()));
        }
        DataObject result = DataObject.empty().put("entries", rows);
        if (entries.size() == LOG_PAGE_SIZE) {
            result.put("nextBefore", entries.get(entries.size() - 1).id());
        } else {
            result.putNull("nextBefore");
        }
        return result;
    }

    private void handleAction(HttpExchange ex, String path, Sessions.Session session) throws IOException, SQLException {
        DataObject body;
        try {
            byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                sendJson(ex, 413, error("Request too large"));
                return;
            }
            body = DataObject.fromJson(bytes);
        } catch (ParsingException e) {
            sendJson(ex, 400, error("Invalid JSON"));
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(body.getString("uuid", ""));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error("Invalid player"));
            return;
        }
        String reason = body.getString("reason", "").trim();
        if (reason.isEmpty()) {
            sendJson(ex, 400, error("A reason is required"));
            return;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            sendJson(ex, 400, error("Reason is too long (max " + MAX_REASON_LENGTH + " characters)"));
            return;
        }
        Optional<LinkedAccount> account = plugin.database().findByUuid(uuid);
        if (account.isEmpty()) {
            sendJson(ex, 404, error("That player is no longer linked"));
            return;
        }
        String actor = session.user.actor() + " (website)";

        switch (path) {
            case "/api/unlink" -> {
                if (!plugin.unlink(account.get(), actor, reason)) {
                    sendJson(ex, 409, error("Already unlinked"));
                    return;
                }
            }
            case "/api/suspend" -> {
                long days = body.getLong("days", 0);
                long hours = body.getLong("hours", 0);
                if (days < 0 || hours < 0 || days > 3650 || hours > 10_000 || days + hours == 0) {
                    sendJson(ex, 400, error("Enter a duration of at least 1 hour"));
                    return;
                }
                if (plugin.database().activeSuspension(uuid).isPresent()) {
                    sendJson(ex, 409, error("Player is already suspended"));
                    return;
                }
                plugin.suspend(account.get(), Duration.ofDays(days).plusHours(hours), reason, actor);
            }
            case "/api/unsuspend" -> {
                if (!plugin.unsuspend(account.get(), reason, actor)) {
                    sendJson(ex, 409, error("Player is not suspended"));
                    return;
                }
            }
            default -> throw new IllegalStateException(path);
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    // ---- HTTP helpers ----

    private static void addSecurityHeaders(HttpExchange ex) {
        var headers = ex.getResponseHeaders();
        headers.set("Content-Security-Policy", "default-src 'self'; img-src 'self' https://mc-heads.net "
                + "https://cdn.discordapp.com; style-src 'self'; script-src 'self'; frame-ancestors 'none'; "
                + "form-action 'self'; base-uri 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private void serveFile(HttpExchange ex, String name) throws IOException {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        send(ex, 200, CONTENT_TYPES.get(extension), files.get(name));
    }

    private void sendMessagePage(HttpExchange ex, int status, String title, String message) throws IOException {
        String html = new String(files.get("message.html"), StandardCharsets.UTF_8)
                .replace("{{title}}", escapeHtml(title))
                .replace("{{message}}", escapeHtml(message));
        send(ex, status, CONTENT_TYPES.get("html"), html.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange ex, int status, Object json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            ex.getResponseBody().write(body);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private String cookie(String name, String value, long maxAgeSeconds) {
        return name + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSeconds
                + (secureCookies ? "; Secure" : "");
    }

    private static Map<String, String> cookies(HttpExchange ex) {
        Map<String, String> result = new HashMap<>();
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers != null) {
            for (String header : headers) {
                for (String part : header.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) {
                        result.putIfAbsent(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                    }
                }
            }
        }
        return result;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> result = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? part : part.substring(0, eq);
                String value = eq < 0 ? "" : part.substring(eq + 1);
                result.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static DataObject error(String message) {
        return DataObject.empty().put("error", message);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
