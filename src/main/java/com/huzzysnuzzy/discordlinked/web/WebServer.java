package com.huzzysnuzzy.discordlinked.web;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.AuditEntry;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerIp;
import com.huzzysnuzzy.discordlinked.storage.PlayerNote;
import com.huzzysnuzzy.discordlinked.storage.PlayerRef;
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
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final int MAX_NOTE_LENGTH = 2000;
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHEET_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss").withZone(ZoneId.systemDefault());
    private static final int LOG_PAGE_SIZE = 100;
    private static final String SESSION_COOKIE = "dl_session";
    private static final String STATE_COOKIE = "dl_state";

    private static final Map<String, Set<String>> LOG_FILTERS = Map.of(
            "admin", Set.of(EventTypes.LINK, EventTypes.UNLINK, EventTypes.SUSPEND, EventTypes.SUSPEND_EDIT,
                    EventTypes.UNSUSPEND, EventTypes.KICK, EventTypes.NOTE, EventTypes.WEB_LOGIN, EventTypes.LOG_EXPORT),
            "kicks", Set.of(EventTypes.KICK),
            "notes", Set.of(EventTypes.NOTE),
            "links", Set.of(EventTypes.LINK, EventTypes.UNLINK),
            "suspensions", Set.of(EventTypes.SUSPEND, EventTypes.SUSPEND_EDIT, EventTypes.UNSUSPEND,
                    EventTypes.DENIED_SUSPENDED),
            "sessions", Set.of(EventTypes.JOIN, EventTypes.LEAVE, EventTypes.SERVER_SWITCH),
            "chat", Set.of(EventTypes.CHAT),
            "commands", Set.of(EventTypes.COMMAND),
            "denied", Set.of(EventTypes.DENIED_UNLINKED, EventTypes.DENIED_SUSPENDED),
            "web", Set.of(EventTypes.WEB_LOGIN, EventTypes.LOG_EXPORT));

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
    private GoogleSheetsExporter sheets;
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
        if (!config.googleSpreadsheetId().isEmpty()) {
            Path keyFile = plugin.dataDirectory().resolve(config.googleServiceAccountFile());
            try {
                sheets = GoogleSheetsExporter.load(config.googleSpreadsheetId(), keyFile);
                plugin.logger().info("Google Sheets export enabled. Share the spreadsheet with {} as an Editor.",
                        sheets.clientEmail());
            } catch (IOException e) {
                plugin.logger().warn("Google Sheets export disabled: {}", e.getMessage());
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
        plugin.logger().info("Discord login redirect (must be listed exactly under Developer Portal -> OAuth2 -> Redirects): {}",
                config.webPublicUrl() + "/callback");
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
                    .put("csrf", session.csrfToken)
                    .put("sheetsEnabled", sheets != null));
            case "GET /api/linked" -> sendJson(ex, 200, linkedList());
            case "GET /api/search" -> sendJson(ex, 200, search(query(ex)));
            case "GET /api/player" -> playerPage(ex, query(ex));
            case "GET /api/logs" -> sendJson(ex, 200, logs(query(ex)));
            case "GET /api/logs/export.csv" -> exportCsv(ex, query(ex), session);
            case "POST /api/logs/export-sheets" -> exportSheets(ex, session);
            case "POST /api/unlink", "POST /api/suspend", "POST /api/suspension/edit", "POST /api/unsuspend",
                 "POST /api/kick", "POST /api/note" -> handleAction(ex, path, session);
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
            String errorCode = e instanceof DiscordOAuth.OAuthException oauthError ? oauthError.code : "";
            sendMessagePage(ex, 502, "Login failed", switch (errorCode) {
                case "invalid_client" -> "Discord says the client ID or secret is wrong (invalid_client). Copy the "
                        + "Client Secret from Developer Portal -> OAuth2 into discord-client-secret (not the bot token), "
                        + "leave discord-client-id empty, and restart the proxy.";
                case "invalid_grant" -> "Discord rejected the login code (invalid_grant). This usually means "
                        + "web-public-url doesn't match the redirect in the Developer Portal, or the page was "
                        + "refreshed. Try logging in again.";
                case "" -> "Couldn't finish the login with Discord. See the proxy console for details.";
                default -> "Discord rejected the login (" + errorCode + "). See the proxy console for details.";
            });
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

    /** The fields every player row shares: identity, link, Discord status, online state and suspension. */
    private DataObject playerJson(UUID uuid, String name, LinkedAccount linked, Suspension suspension)
            throws SQLException {
        Optional<Player> online = plugin.proxy().getPlayer(uuid);
        DataObject row = DataObject.empty()
                .put("uuid", uuid.toString())
                .put("minecraftName", name)
                .put("linked", linked != null)
                .put("online", online.isPresent())
                .put("server", online.flatMap(Player::getCurrentServer).map(s -> s.getServerInfo().getName()).orElse(""));
        if (linked != null) {
            if (linked.discordName() == null) {
                plugin.bot().refreshProfile(linked.discordId());
            }
            DiscordBot.MemberInfo member = plugin.bot().memberInfo(linked.discordId());
            row.put("discordId", String.valueOf(linked.discordId()))
                    .put("discordName", linked.discordName() == null ? "Unknown user" : linked.discordName())
                    .put("discordAvatar", linked.discordAvatar() == null
                            ? DiscordOAuth.avatarUrl(linked.discordId(), null) : linked.discordAvatar())
                    .put("linkedAt", linked.linkedAt().toEpochMilli())
                    .put("lastSeen", linked.lastSeen() == null ? null : linked.lastSeen().toEpochMilli())
                    .put("isAdmin", member != null && member.admin())
                    .put("inGuild", member == null || member.inGuild());
        } else {
            Instant lastActivity = plugin.database().lastActivity(uuid);
            row.put("lastSeen", lastActivity == null ? null : lastActivity.toEpochMilli())
                    .put("isAdmin", false)
                    .put("inGuild", true);
        }
        row.put("suspension", suspension == null ? null : suspensionJson(suspension));
        return row;
    }

    private static DataObject suspensionJson(Suspension s) {
        return DataObject.empty()
                .put("id", s.id())
                .put("reason", s.reason())
                .put("createdBy", s.createdBy())
                .put("createdAt", s.createdAt().toEpochMilli())
                .put("expiresAt", s.expiresAt().toEpochMilli())
                .put("liftedAt", s.liftedAt() == null ? null : s.liftedAt().toEpochMilli())
                .put("liftedBy", s.liftedBy())
                .put("liftReason", s.liftReason())
                .put("active", s.isActive());
    }

    private DataArray linkedList() throws SQLException {
        Map<UUID, Suspension> suspensions = plugin.database().activeSuspensions();
        DataArray result = DataArray.empty();
        for (LinkedAccount account : plugin.database().allLinked()) {
            result.add(playerJson(account.uuid(), account.minecraftName(), account, suspensions.get(account.uuid())));
        }
        return result;
    }

    private DataArray search(Map<String, String> query) throws SQLException {
        String q = query.getOrDefault("q", "").trim();
        DataArray result = DataArray.empty();
        if (q.isEmpty()) {
            return result;
        }
        for (PlayerRef ref : plugin.database().searchPlayers(q, 20)) {
            LinkedAccount linked = ref.discordId() == null ? null : plugin.database().findByUuid(ref.uuid()).orElse(null);
            result.add(playerJson(ref.uuid(), ref.name(), linked,
                    plugin.database().activeSuspension(ref.uuid()).orElse(null)));
        }
        return result;
    }

    private void playerPage(HttpExchange ex, Map<String, String> query) throws IOException, SQLException {
        UUID uuid = parseUuid(query.get("uuid"));
        Optional<PlayerRef> ref = uuid == null ? Optional.empty() : plugin.database().findPlayer(uuid);
        if (ref.isEmpty()) {
            sendJson(ex, 404, error("No player with that UUID has ever joined"));
            return;
        }
        LinkedAccount linked = plugin.database().findByUuid(uuid).orElse(null);
        DataObject player = playerJson(uuid, ref.get().name(), linked,
                plugin.database().activeSuspension(uuid).orElse(null));

        DataArray suspensions = DataArray.empty();
        for (Suspension s : plugin.database().suspensions(uuid)) {
            suspensions.add(suspensionJson(s));
        }
        DataArray notes = DataArray.empty();
        for (PlayerNote note : plugin.database().notes(uuid)) {
            notes.add(DataObject.empty().put("note", note.note()).put("author", note.author())
                    .put("createdAt", note.createdAt().toEpochMilli()));
        }
        DataArray ips = DataArray.empty();
        for (PlayerIp ip : plugin.database().ips(uuid)) {
            DataArray others = DataArray.empty();
            for (UUID other : plugin.database().playersOnIp(ip.ip(), uuid)) {
                plugin.database().findPlayer(other).ifPresent(o -> others.add(DataObject.empty()
                        .put("uuid", o.uuid().toString()).put("name", o.name()).put("linked", o.discordId() != null)));
            }
            ips.add(DataObject.empty().put("ip", ip.ip()).put("firstSeen", ip.firstSeen().toEpochMilli())
                    .put("lastSeen", ip.lastSeen().toEpochMilli()).put("times", ip.times()).put("others", others));
        }
        sendJson(ex, 200, DataObject.empty().put("player", player).put("suspensions", suspensions)
                .put("notes", notes).put("ips", ips));
    }

    private List<AuditEntry> findLogs(Map<String, String> query, long before, int limit) throws SQLException {
        Set<String> types = LOG_FILTERS.getOrDefault(query.getOrDefault("filter", "all"), Set.of());
        return plugin.database().searchEvents(types, query.getOrDefault("q", "").trim(),
                parseUuid(query.get("uuid")), before, limit);
    }

    private DataObject logs(Map<String, String> query) throws SQLException {
        List<AuditEntry> entries = findLogs(query, parseLong(query.get("before")), LOG_PAGE_SIZE);
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
        result.put("nextBefore", entries.size() == LOG_PAGE_SIZE ? entries.get(entries.size() - 1).id() : null);
        return result;
    }

    private static final List<String> EXPORT_HEADER =
            List.of("Time", "Event", "Player", "UUID", "Discord ID", "By", "Details");

    private List<List<String>> exportRows(Map<String, String> query) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        for (AuditEntry e : findLogs(query, 0, MAX_EXPORT_ROWS)) {
            rows.add(List.of(EXPORT_TIME.format(e.timestamp()), e.eventType(),
                    e.minecraftName() == null ? "" : e.minecraftName(),
                    e.uuid() == null ? "" : e.uuid().toString(),
                    e.discordId() == null ? "" : String.valueOf(e.discordId()),
                    e.actor() == null ? "" : e.actor(),
                    e.detail() == null ? "" : e.detail()));
        }
        return rows;
    }

    private void exportCsv(HttpExchange ex, Map<String, String> query, Sessions.Session session)
            throws IOException, SQLException {
        StringBuilder csv = new StringBuilder();
        List<List<String>> rows = new ArrayList<>();
        rows.add(EXPORT_HEADER);
        rows.addAll(exportRows(query));
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvCell(row.get(i)));
            }
            csv.append("\r\n");
        }
        plugin.audit().log(null, null, session.user.id(), EventTypes.LOG_EXPORT,
                "CSV, " + (rows.size() - 1) + " rows, filter=" + query.getOrDefault("filter", "all"), session.user.actor());
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"discordlinked-logs-" + FILE_TIME.format(Instant.now()) + ".csv\"");
        // BOM so Excel reads UTF-8 correctly; Google Sheets ignores it.
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        send(ex, 200, "text/csv; charset=utf-8", body);
    }

    /** Quotes a CSV field, and stops spreadsheet apps treating chat like "=HYPERLINK(...)" as a formula. */
    private static String csvCell(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void exportSheets(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        if (sheets == null) {
            sendJson(ex, 400, error("Google Sheets export isn't set up. See the README."));
            return;
        }
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        Map<String, String> query = new HashMap<>();
        for (String key : List.of("filter", "q", "uuid")) {
            query.put(key, body.getString(key, ""));
        }
        List<List<String>> rows = exportRows(query);
        String title = "Logs " + query.get("filter").replaceAll("[^a-z]", "") + " " + SHEET_TIME.format(Instant.now());
        try {
            String url = sheets.export(title.replace("  ", " "), EXPORT_HEADER, rows);
            plugin.audit().log(null, null, session.user.id(), EventTypes.LOG_EXPORT,
                    "Google Sheets, " + rows.size() + " rows, filter=" + query.get("filter"), session.user.actor());
            sendJson(ex, 200, DataObject.empty().put("url", url).put("rows", rows.size()));
        } catch (IOException e) {
            plugin.logger().warn("Google Sheets export failed: {}", e.getMessage());
            sendJson(ex, 502, error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 503, error("Export interrupted"));
        }
    }

    /** Reads a small JSON body, or sends an error and returns null. */
    private DataObject readJson(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            sendJson(ex, 413, error("Request too large"));
            return null;
        }
        try {
            return DataObject.fromJson(bytes);
        } catch (ParsingException e) {
            sendJson(ex, 400, error("Invalid JSON"));
            return null;
        }
    }

    private void handleAction(HttpExchange ex, String path, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        UUID uuid = parseUuid(body.getString("uuid", ""));
        Optional<PlayerRef> player = uuid == null ? Optional.empty() : plugin.database().findPlayer(uuid);
        if (player.isEmpty()) {
            sendJson(ex, 404, error("Unknown player"));
            return;
        }
        boolean isNote = path.equals("/api/note");
        String reason = body.getString(isNote ? "note" : "reason", "").trim();
        int maxLength = isNote ? MAX_NOTE_LENGTH : MAX_REASON_LENGTH;
        if (reason.isEmpty()) {
            sendJson(ex, 400, error(isNote ? "The note is empty" : "A reason is required"));
            return;
        }
        if (reason.length() > maxLength) {
            sendJson(ex, 400, error("Too long (max " + maxLength + " characters)"));
            return;
        }
        String actor = session.user.actor() + " (website)";
        PlayerRef target = player.get();

        switch (path) {
            case "/api/unlink" -> {
                Optional<LinkedAccount> account = plugin.database().findByUuid(uuid);
                if (account.isEmpty() || !plugin.unlink(account.get(), actor, reason, true)) {
                    sendJson(ex, 409, error("That player isn't linked"));
                    return;
                }
            }
            case "/api/suspend", "/api/suspension/edit" -> {
                Duration duration = parseDuration(body);
                if (duration == null) {
                    sendJson(ex, 400, error("Enter a duration of at least 1 hour"));
                    return;
                }
                boolean suspended = plugin.database().activeSuspension(uuid).isPresent();
                if (path.equals("/api/suspend")) {
                    if (suspended) {
                        sendJson(ex, 409, error("Player is already suspended. Use Edit instead."));
                        return;
                    }
                    plugin.suspend(target, duration, reason, actor);
                } else if (!plugin.editSuspension(target, duration, reason, actor)) {
                    sendJson(ex, 409, error("Player is not suspended"));
                    return;
                }
            }
            case "/api/kick" -> {
                if (!plugin.kick(target, reason, actor)) {
                    sendJson(ex, 409, error("Player is not online"));
                    return;
                }
            }
            case "/api/unsuspend" -> {
                if (!plugin.unsuspend(target, reason, actor)) {
                    sendJson(ex, 409, error("Player is not suspended"));
                    return;
                }
            }
            case "/api/note" -> plugin.addNote(target, reason, actor);
            default -> throw new IllegalStateException(path);
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    /** Days + hours from the request body, or null if out of range. */
    private static Duration parseDuration(DataObject body) {
        long days = body.getLong("days", 0);
        long hours = body.getLong("hours", 0);
        if (days < 0 || hours < 0 || days > 3650 || hours > 10_000 || days + hours == 0) {
            return null;
        }
        return Duration.ofDays(days).plusHours(hours);
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

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isEmpty() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
