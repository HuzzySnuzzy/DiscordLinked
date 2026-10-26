package com.huzzysnuzzy.discordlinked.web;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.LiveEvents;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.SettingsEditor;
import com.huzzysnuzzy.discordlinked.spy.InventoryRequests;
import com.huzzysnuzzy.discordlinked.storage.InventorySnapshotRecord;
import com.huzzysnuzzy.discordlinked.storage.PendingRestore;
import com.huzzysnuzzy.discordlinked.TimeFormat;
import com.huzzysnuzzy.discordlinked.spy.SpyManager;
import com.huzzysnuzzy.discordlinked.storage.AuditEntry;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.fad.FadActions;
import com.huzzysnuzzy.discordlinked.storage.CreditAccount;
import com.huzzysnuzzy.discordlinked.storage.CreditTransaction;
import com.huzzysnuzzy.discordlinked.storage.EarnedAdvancement;
import com.huzzysnuzzy.discordlinked.storage.InteractionRecord;
import com.huzzysnuzzy.discordlinked.storage.LeaderboardRow;
import com.huzzysnuzzy.discordlinked.fad.Shop;
import com.huzzysnuzzy.discordlinked.storage.KnownPlayer;
import com.huzzysnuzzy.discordlinked.storage.ShopOverride;
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
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The website. Members of the Discord server log in with Discord (if {@code web-member-access} is on) and
 * get the community view; holders of {@code admin-role-id} get the admin view, and can switch to the
 * community view by going off duty. Roles are re-checked every few minutes while people stay logged in.
 */
public final class WebServer {

    private static final Duration ROLE_RECHECK = Duration.ofMinutes(5);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REASON_LENGTH = 500;
    /** The only requests allowed while the admin is in a spy session. */
    private static final Set<String> SPY_ALLOWED = Set.of("GET /api/me", "GET /api/spy", "GET /api/poll",
            "POST /api/spy/end", "POST /api/spy/kick", "POST /api/spy/suspend");
    /** Everything the community view (non-admins and off-duty admins) may call. */
    private static final Set<String> MEMBER_ROUTES = Set.of("GET /api/me", "GET /api/home", "GET /api/community",
            "GET /api/members", "GET /api/search", "GET /api/player", "GET /api/poll", "POST /api/duty",
            "GET /api/shop", "POST /api/shop/checkout");
    /** Log events the community view hears about, reduced to "this player changed" so nothing else leaks. */
    private static final Set<String> PRESENCE_EVENTS = Set.of(EventTypes.JOIN, EventTypes.LEAVE,
            EventTypes.SERVER_SWITCH, EventTypes.LINK, EventTypes.UNLINK, EventTypes.INTERACTION,
            EventTypes.ADVANCEMENT, EventTypes.CREDITS_ADJUST, EventTypes.SHOP_PURCHASE);
    private static final int MAX_NOTE_LENGTH = 2000;
    private static final long MAX_CREDIT_ADJUSTMENT = 1_000_000_000L;
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

    private static final Map<String, Set<String>> LOG_FILTERS = Map.ofEntries(
            Map.entry("admin", Set.of(EventTypes.LINK, EventTypes.UNLINK, EventTypes.SUSPEND, EventTypes.SUSPEND_EDIT,
                    EventTypes.UNSUSPEND, EventTypes.KICK, EventTypes.NOTE, EventTypes.WEB_LOGIN, EventTypes.LOG_EXPORT,
                    EventTypes.SPY_START, EventTypes.SPY_END, EventTypes.INVENTORY_VIEW,
                    EventTypes.INVENTORY_SNAPSHOT, EventTypes.INVENTORY_RESTORE, EventTypes.SETTINGS_CHANGE, EventTypes.DUTY,
                    EventTypes.TELEPORT,
                    EventTypes.CREDITS_ADJUST)),
            Map.entry("kicks", Set.of(EventTypes.KICK)),
            Map.entry("notes", Set.of(EventTypes.NOTE)),
            Map.entry("links", Set.of(EventTypes.LINK, EventTypes.UNLINK)),
            Map.entry("suspensions", Set.of(EventTypes.SUSPEND, EventTypes.SUSPEND_EDIT, EventTypes.UNSUSPEND,
                    EventTypes.DENIED_SUSPENDED)),
            Map.entry("sessions", Set.of(EventTypes.JOIN, EventTypes.LEAVE, EventTypes.SERVER_SWITCH)),
            Map.entry("chat", Set.of(EventTypes.CHAT)),
            Map.entry("commands", Set.of(EventTypes.COMMAND)),
            Map.entry("denied", Set.of(EventTypes.DENIED_UNLINKED, EventTypes.DENIED_SUSPENDED)),
            Map.entry("web", Set.of(EventTypes.WEB_LOGIN, EventTypes.LOG_EXPORT, EventTypes.DUTY)),
            Map.entry("spy", Set.of(EventTypes.SPY_START, EventTypes.SPY_END)),
            Map.entry("inventory", Set.of(EventTypes.INVENTORY_VIEW, EventTypes.INVENTORY_SNAPSHOT,
                    EventTypes.INVENTORY_RESTORE)),
            Map.entry("deaths", Set.of(EventTypes.DEATH)),
            Map.entry("settings", Set.of(EventTypes.SETTINGS_CHANGE)),
            Map.entry("community", Set.of(EventTypes.INTERACTION, EventTypes.ADVANCEMENT, EventTypes.CREDITS_ADJUST,
                    EventTypes.SHOP_PURCHASE)),
            Map.entry("shop", Set.of(EventTypes.SHOP_PURCHASE)));

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
    private volatile DiscordOAuth oauth;
    private GoogleSheetsExporter sheets;
    private volatile boolean secureCookies;
    private volatile String publicUrl;
    private CloudflareTunnel tunnel;

    public WebServer(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Returns false (after logging why) if the website can't run with the current config. */
    public boolean start() throws IOException {
        PluginConfig config = plugin.config();
        if (!config.botConfigured() || config.adminRoleId() == 0L) {
            plugin.logger().warn("Website not started: it needs bot-token, guild-id and admin-role-id set in "
                    + "config.properties.");
            return false;
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
        // Through the tunnel only Cloudflare (via the local cloudflared) talks to us, so stay off the network.
        String bindAddress = config.webTunnel() ? "127.0.0.1" : config.webBindAddress();
        server = HttpServer.create(new InetSocketAddress(bindAddress, config.webPort()), 0);
        // Cached rather than fixed: each open page holds one thread for its live event stream.
        executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "DiscordLinked-Web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        plugin.audit().live().subscribe(liveListener);
        webSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "DiscordLinked-WebPresence");
            thread.setDaemon(true);
            return thread;
        });
        webSweeper.scheduleAtFixedRate(this::sweepOnWebsite, 10, 10, TimeUnit.SECONDS);

        if (config.webTunnel()) {
            secureCookies = true;
            plugin.logger().info("Website starting; setting up its https:// address...");
            domain = new CloudflareDomain(plugin.dataDirectory(), plugin.dataDirectory().resolve("bin"), plugin.logger(),
                    ready -> {
                        // Only now that it works: save the address it was asked for.
                        String[] wanted = pendingAddress;
                        if (wanted != null) {
                            try {
                                new SettingsEditor(plugin).setWebAddress(wanted[0], wanted[1]);
                            } catch (IOException e) {
                                plugin.logger().warn("Could not save the website address: {}", e.getMessage());
                            }
                            pendingAddress = null;
                        }
                        startTunnel(chooseTunnel());
                    });
            startTunnel(chooseTunnel());
        } else {
            String url = config.webPublicUrl();
            if (url.isEmpty() || url.contains("://localhost")) {
                url = "http://" + detectPublicIp().orElse("localhost") + ":" + config.webPort();
            }
            publicUrl = url;
            secureCookies = url.startsWith("https://");
            if (!config.oauthClientSecret().isEmpty()) {
                oauth = new DiscordOAuth(config.oauthClientSecret(), url + "/callback");
                plugin.logger().info("Discord login button enabled. Its redirect must be listed under Developer "
                        + "Portal -> OAuth2 -> Redirects: {}/callback", url);
            }
            plugin.logger().info("Admin website running at {}. Run /dla in Discord or in game to log in.", url);
        }
        return true;
    }

    // ---- The https address (Cloudflare tunnel) ----

    private CloudflareDomain domain;
    /** The address being set up from the Settings page ({domain, prefix}), saved once it works. */
    private volatile String[] pendingAddress;
    /** Every tunnel started, so a stop also ends one that was being replaced. */
    private final List<CloudflareTunnel> startedTunnels = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The owner's own address if one is set up (token or Settings page), otherwise a quick tunnel. */
    private CloudflareTunnel.Mode chooseTunnel() {
        PluginConfig c = plugin.config();
        if (!c.webTunnelToken().isEmpty() && !c.webHostname().isEmpty()) {
            return CloudflareTunnel.token(c.webTunnelToken(), "https://" + c.webHostname());
        }
        if (domain != null && domain.configured()) {
            return CloudflareTunnel.named(domain.credentials(), domain.tunnelId(), c.webPort(), "https://" + domain.hostname());
        }
        return CloudflareTunnel.quick(c.webPort());
    }

    /**
     * Starts a tunnel. The one running before keeps serving until the new one is connected, so switching address
     * never leaves the website unreachable. A tunnel on the owner's domain that can't connect falls back to a
     * quick tunnel.
     */
    private synchronized void startTunnel(CloudflareTunnel.Mode mode) {
        CloudflareTunnel previous = tunnel;
        boolean quick = mode.name().equals("quick");
        CloudflareTunnel[] self = new CloudflareTunnel[1];
        self[0] = new CloudflareTunnel(plugin.dataDirectory().resolve("bin"), mode, plugin.logger(), url -> {
            publicUrl = url;
            // Discord login needs a redirect address that doesn't change, so only on the owner's own address.
            updateDiscordLogin(quick ? null : url);
            plugin.logger().info(quick ? "Website is live at {} (the address changes when the proxy restarts). "
                    + "Run /dla in Discord or in game to log in." : "Website is live at {}. Run /dla in Discord or in game "
                    + "to log in.", url);
            if (previous != null && previous != self[0]) {
                // Keep the old address working a little longer, so whoever switched can follow the link to the new one.
                webSweeper.schedule(previous::stop, 90, TimeUnit.SECONDS);
            }
        }, quick ? null : detail -> {
            if (domain != null) {
                domain.runFailed(detail);
            }
            synchronized (this) {
                if (tunnel != self[0]) {
                    return;
                }
                if (previous != null && previous.mode().name().equals("quick")) {
                    tunnel = previous; // still running and serving: keep it
                } else {
                    startTunnel(CloudflareTunnel.quick(plugin.config().webPort()));
                }
            }
        });
        tunnel = self[0];
        startedTunnels.add(self[0]);
        self[0].start();
    }

    /** Turns the "Log in with Discord" button on for {@code url} (if the client secret is set), or off for null. */
    private void updateDiscordLogin(String url) {
        String secret = plugin.config().oauthClientSecret();
        if (url == null || secret.isEmpty()) {
            oauth = null;
            if (url != null) {
                plugin.logger().info("For a \"Log in with Discord\" button on {}: put the Client Secret from Discord's Developer "
                        + "Portal -> OAuth2 in discord-client-secret, add {}/callback under OAuth2 -> Redirects, and restart.", url, url);
            }
            return;
        }
        oauth = new DiscordOAuth(secret, url + "/callback");
        plugin.logger().info("Log in with Discord is on. Its redirect must be listed under Developer Portal -> OAuth2 -> "
                + "Redirects: {}/callback", url);
    }

    private DataObject domainJson() {
        PluginConfig c = plugin.config();
        boolean token = !c.webTunnelToken().isEmpty();
        DataObject result = DataObject.empty()
                .put("tunnel", c.webTunnel())
                .put("prefix", pendingAddress != null ? pendingAddress[1] : c.webDomainPrefix())
                .put("domain", pendingAddress != null ? pendingAddress[0] : c.webDomain())
                .put("hostname", c.webHostname())
                .put("tokenMode", token)
                .put("publicUrl", publicUrl)
                .put("current", tunnel == null ? null : tunnel.mode().name())
                .put("discordLogin", oauth != null)
                .put("clientSecretSet", !c.oauthClientSecret().isEmpty());
        if (domain != null) {
            result.put("state", domain.state().name().toLowerCase())
                    .put("loginUrl", domain.loginUrl())
                    .put("message", domain.message())
                    .put("connectedHostname", domain.hostname());
        }
        return result;
    }

    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern DOMAIN = Pattern.compile("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    /** Settings page, "Your own address": saves the address and connects it (logging in to Cloudflare if needed). */
    private void domainConnect(HttpExchange ex, Sessions.Session session) throws IOException {
        if (domain == null) {
            sendJson(ex, 409, error("Your own address needs web-access=tunnel."));
            return;
        }
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String prefix = body.getString("prefix", "").trim().toLowerCase();
        String name = body.getString("domain", "").trim().toLowerCase().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (!prefix.isEmpty() && !DNS_LABEL.matcher(prefix).matches()) {
            sendJson(ex, 400, error("The part before your domain can only have letters, numbers and dashes (like dla or panel)."));
            return;
        }
        if (!DOMAIN.matcher(name).matches()) {
            sendJson(ex, 400, error("Enter your domain like example.com or example.nl."));
            return;
        }
        String hostname = prefix.isEmpty() ? name : prefix + "." + name;
        plugin.audit().log(null, null, session.user.id(), EventTypes.SETTINGS_CHANGE, "website address: " + hostname,
                session.user.actor() + " (website)");
        if (!plugin.config().webTunnelToken().isEmpty()) {
            // The tunnel is set up in Cloudflare's dashboard; just use the new address.
            new SettingsEditor(plugin).setWebAddress(name, prefix);
            startTunnel(chooseTunnel());
        } else {
            pendingAddress = new String[] {name, prefix};
            domain.connect(hostname);
            // Give cloudflared a moment to print the Cloudflare link (or to finish, if no login is needed).
            for (int i = 0; i < 40 && domain.state() == CloudflareDomain.State.WAITING && domain.loginUrl() == null; i++) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        sendJson(ex, 200, domainJson());
    }

    private void domainDisconnect(HttpExchange ex, Sessions.Session session) throws IOException {
        if (domain == null) {
            sendJson(ex, 409, error("Your own address needs web-access=tunnel."));
            return;
        }
        domain.disconnect();
        new SettingsEditor(plugin).setWebAddress("", null);
        plugin.audit().log(null, null, session.user.id(), EventTypes.SETTINGS_CHANGE,
                "website address: back to an automatic one", session.user.actor() + " (website)");
        startTunnel(chooseTunnel());
        sendJson(ex, 200, domainJson());
    }

    /** The address admins open, or null while the HTTPS tunnel is still starting. */
    public String publicUrl() {
        return publicUrl;
    }

    /**
     * A one-time login link, or null if the website isn't reachable yet. Whether the user may log in (and
     * with which view) is checked when the link is used.
     */
    public String createLoginLink(long discordId, String name, String avatarUrl) {
        String base = publicUrl;
        if (base == null) {
            return null;
        }
        String avatar = avatarUrl == null ? DiscordOAuth.avatarUrl(discordId, null) : avatarUrl;
        String token = sessions.newLoginLink(new DiscordOAuth.DiscordUser(discordId, name, avatar));
        return base + "/login/link?t=" + token;
    }

    private static Optional<String> detectPublicIp() {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("https://checkip.amazonaws.com")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String ip = response.body().trim();
            return response.statusCode() == 200 && ip.matches("[0-9a-fA-F.:]+") ? Optional.of(ip) : Optional.empty();
        } catch (IOException | InterruptedException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void stop() {
        plugin.audit().live().unsubscribe(liveListener);
        if (webSweeper != null) {
            webSweeper.shutdownNow();
        }
        synchronized (liveLock) {
            liveLock.notifyAll();
        }
        startedTunnels.forEach(CloudflareTunnel::stop);
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    // ---- Routing ----

    private void handle(HttpExchange ex) {
        try {
            addSecurityHeaders(ex);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                handleApi(ex, method, path);
                return;
            }
            if (method.equals("POST") && path.equals("/login/link")) {
                useLoginLink(ex);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(ex, 405, "Method not allowed");
                return;
            }
            switch (path) {
                case "/" -> {
                    if (currentSession(ex) != null) {
                        serveFile(ex, "index.html");
                    } else {
                        serveLoginPage(ex);
                    }
                }
                case "/login/link" -> loginLinkPage(ex);
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
        } finally {
            // Closed here, not with try-with-resources, so the error response above still gets sent.
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException, SQLException {
        Sessions.Session session = currentSession(ex);
        if (session == null) {
            sendJson(ex, 401, error("Not logged in"));
            return;
        }
        markOnWebsite(session.user.id());
        if (method.equals("POST")) {
            String csrf = ex.getRequestHeaders().getFirst("X-CSRF-Token");
            if (csrf == null || !csrf.equals(session.csrfToken)) {
                sendJson(ex, 403, error("Invalid CSRF token, reload the page"));
                return;
            }
        }
        String route = method + " " + path;
        if (plugin.spy().current(session.user.id()) != null && !SPY_ALLOWED.contains(route)) {
            sendJson(ex, 423, error("You're in a spy session. End it first.").put("locked", true));
            return;
        }
        boolean staff = session.staff();
        if (!staff && !MEMBER_ROUTES.contains(route)) {
            sendJson(ex, 403, error(session.admin ? "You're off duty. Switch on duty to use the admin pages."
                    : "Only admins can do that."));
            return;
        }
        switch (route) {
            case "GET /api/home" -> sendJson(ex, 200, home());
            case "GET /api/members" -> sendJson(ex, 200, memberList());
            case "POST /api/duty" -> setDuty(ex, session);
            case "GET /api/online" -> sendJson(ex, 200, onlineList(session));
            case "GET /api/spy" -> sendJson(ex, 200, spyStatus(session));
            case "POST /api/spy/start" -> spyStart(ex, session);
            case "POST /api/spy/end", "POST /api/spy/kick", "POST /api/spy/suspend" -> spyAction(ex, path, session);
            case "GET /api/me" -> sendJson(ex, 200, meJson(session));
            case "GET /api/linked" -> sendJson(ex, 200, linkedList());
            case "GET /api/search" -> sendJson(ex, 200, search(query(ex), staff));
            case "GET /api/player" -> {
                if (staff) {
                    playerPage(ex, query(ex));
                } else {
                    publicPlayerPage(ex, query(ex));
                }
            }
            case "GET /api/player/inventory" -> inventory(ex, query(ex), session);
            case "GET /api/player/snapshots" -> snapshots(ex, query(ex));
            case "GET /api/player/snapshot" -> snapshotPreview(ex, query(ex));
            case "POST /api/player/snapshot/save" -> snapshotSave(ex, session);
            case "POST /api/player/snapshot/restore" -> snapshotRestore(ex, session);
            case "GET /api/poll" -> poll(ex, session);
            case "GET /api/settings", "POST /api/settings", "POST /api/settings/cleanup", "POST /api/settings/compact",
                 "GET /api/settings/domain", "POST /api/settings/domain/connect", "POST /api/settings/domain/disconnect",
                 "POST /api/settings/domain/login-link" ->
                    settings(ex, method, path, session);
            case "GET /api/community" -> sendJson(ex, 200, community(query(ex), staff));
            case "POST /api/credits/adjust" -> adjustCredits(ex, session);
            case "GET /api/shop" -> sendJson(ex, 200, shop(session));
            case "POST /api/shop/checkout" -> shopCheckout(ex, session);
            case "POST /api/shop/item" -> shopItem(ex, session);
            case "POST /api/player/teleport" -> teleport(ex, session);
            case "GET /api/logs" -> sendJson(ex, 200, logs(query(ex)));
            case "GET /api/logs/export.csv" -> exportCsv(ex, query(ex), session);
            case "POST /api/logs/export-sheets" -> exportSheets(ex, session);
            case "POST /api/unlink", "POST /api/suspend", "POST /api/suspension/edit", "POST /api/unsuspend",
                 "POST /api/kick", "POST /api/note" -> handleAction(ex, path, session);
            default -> sendJson(ex, 404, error("Unknown endpoint"));
        }
    }

    // ---- Login ----

    private enum Access { ADMIN, MEMBER, NONE }

    /** Who may use the website: admins, and (if web-member-access is on) anyone in the Discord server. */
    private Access access(long userId) {
        if (!plugin.bot().isReady()) {
            return Access.NONE;
        }
        DiscordBot.MemberInfo member = plugin.bot().checkMember(userId);
        if (member.admin()) {
            return Access.ADMIN;
        }
        return member.inGuild() && plugin.config().webMemberAccess() ? Access.MEMBER : Access.NONE;
    }

    private void sendNoAccess(HttpExchange ex) throws IOException {
        sendMessagePage(ex, 403, "No access", plugin.config().webMemberAccess()
                ? "This website is only for members of the Discord server."
                : "Your Discord account doesn't have the admin role for this website.");
    }

    private DataObject meJson(Sessions.Session session) {
        boolean staff = session.staff();
        return DataObject.empty()
                .put("id", String.valueOf(session.user.id()))
                .put("name", session.user.name())
                .put("avatar", session.user.avatarUrl())
                .put("csrf", session.csrfToken)
                .put("isAdmin", session.admin)
                .put("onDuty", session.onDuty)
                .put("staff", staff)
                .put("sheetsEnabled", staff && sheets != null)
                .put("isOwner", staff && isOwner(session))
                .put("communityEnabled", plugin.config().fadEnabled())
                .put("shopOpen", plugin.community().shop().open());
    }

    private void setDuty(HttpExchange ex, Sessions.Session session) throws IOException {
        if (!session.admin) {
            sendJson(ex, 403, error("Only admins have an admin view."));
            return;
        }
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        boolean onDuty = body.getBoolean("onDuty", true);
        if (session.onDuty != onDuty) {
            session.onDuty = onDuty;
            plugin.audit().log(null, null, session.user.id(), EventTypes.DUTY, onDuty
                    ? "went on duty (admin view)" : "went off duty (community view)", session.user.actor());
        }
        sendJson(ex, 200, meJson(session));
    }

    private void serveLoginPage(HttpExchange ex) throws IOException {
        String button = oauth == null ? ""
                : "<a class=\"btn btn-ghost\" href=\"/login\">Or log in with Discord</a>";
        String html = new String(files.get("login.html"), StandardCharsets.UTF_8).replace("{{oauth}}", button);
        send(ex, 200, CONTENT_TYPES.get("html"), html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Shows a "continue" button instead of logging in straight away, so link scanners and previews that
     * open URLs can't use up the one-time link.
     */
    private void loginLinkPage(HttpExchange ex) throws IOException {
        String token = query(ex).get("t");
        Sessions.LoginLink link = sessions.peekLoginLink(token);
        if (link == null) {
            sendMessagePage(ex, 410, "Link expired", "This login link was already used or has expired. "
                    + "Run /dla again for a new one.");
            return;
        }
        String html = new String(files.get("message.html"), StandardCharsets.UTF_8)
                .replace("{{title}}", "Log in as " + escapeHtml(link.user().name()))
                .replace("{{message}}", "This one-time link logs you into the website."
                        + "</p><form method=\"post\" action=\"/login/link\" class=\"inline-form\">"
                        + "<input type=\"hidden\" name=\"t\" value=\"" + escapeHtml(token) + "\">"
                        + "<button class=\"btn\" type=\"submit\">Continue</button></form><p hidden>");
        send(ex, 200, CONTENT_TYPES.get("html"), html.getBytes(StandardCharsets.UTF_8));
    }

    private void useLoginLink(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY_BYTES);
        String token = null;
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            if (part.startsWith("t=")) {
                token = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            }
        }
        Sessions.LoginLink link = sessions.consumeLoginLink(token);
        if (link == null) {
            sendMessagePage(ex, 410, "Link expired", "This login link was already used or has expired. "
                    + "Run /dla again for a new one.");
            return;
        }
        Access access = access(link.user().id());
        if (access == Access.NONE) {
            sendNoAccess(ex);
            return;
        }
        Sessions.Session session = sessions.create(link.user(), access == Access.ADMIN);
        plugin.audit().log(null, null, link.user().id(), EventTypes.WEB_LOGIN, "logged in with a /dla link"
                + (access == Access.ADMIN ? "" : " (community view)"), link.user().actor());
        ex.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, session.token,
                Sessions.SESSION_LIFETIME.toSeconds()));
        redirect(ex, "/");
    }

    private void startLogin(HttpExchange ex) throws IOException {
        if (oauth == null) {
            sendMessagePage(ex, 404, "Use /dla", "Run /dla in Discord or in game to get a login link.");
            return;
        }
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
        if (oauth == null) {
            sendMessagePage(ex, 404, "Use /dla", "Run /dla in Discord or in game to get a login link.");
            return;
        }
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
        Access access = access(user.id());
        if (access == Access.NONE) {
            plugin.audit().log(null, null, user.id(), EventTypes.WEB_LOGIN, plugin.config().webMemberAccess()
                    ? "denied: not in the Discord server" : "denied: missing admin role", user.actor());
            sendNoAccess(ex);
            return;
        }
        Sessions.Session session = sessions.create(user, access == Access.ADMIN);
        plugin.audit().log(null, null, user.id(), EventTypes.WEB_LOGIN,
                access == Access.ADMIN ? "logged in" : "logged in (community view)", user.actor());
        ex.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, session.token,
                Sessions.SESSION_LIFETIME.toSeconds()));
        redirect(ex, "/");
    }

    private void logout(HttpExchange ex) throws IOException {
        Sessions.Session session = sessions.get(cookies(ex).get(SESSION_COOKIE));
        if (session != null && webActive.remove(session.user.id()) != null) {
            webPresenceChanged(session.user.id());
        }
        sessions.invalidate(cookies(ex).get(SESSION_COOKIE));
        ex.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, "", 0));
        redirect(ex, "/");
    }

    /**
     * The logged-in session, or null. Drops the session if the user may no longer use the website, and
     * moves admins who lost the role to the community view.
     */
    private Sessions.Session currentSession(HttpExchange ex) {
        Sessions.Session session = sessions.get(cookies(ex).get(SESSION_COOKIE));
        if (session == null) {
            return null;
        }
        if (Duration.between(session.lastRoleCheck, Instant.now()).compareTo(ROLE_RECHECK) > 0) {
            Access access = access(session.user.id());
            if (access == Access.NONE) {
                sessions.invalidate(session.token);
                return null;
            }
            session.admin = access == Access.ADMIN;
            session.lastRoleCheck = Instant.now();
        }
        return session;
    }

    // ---- API ----

    /** The fields every player row shares: identity, link, Discord status, online state and suspension. */
    private DataObject playerJson(UUID uuid, String name, LinkedAccount linked, Suspension suspension)
            throws SQLException {
        DataObject row = publicPlayerJson(uuid, name, linked);
        DiscordBot.MemberInfo member = linked == null ? null : plugin.bot().memberInfo(linked.discordId());
        return row.put("inGuild", member == null || member.inGuild())
                .put("suspension", suspension == null ? null : suspensionJson(suspension));
    }

    /** What anyone logged in may see about a player: identity, Discord profile, online state and staff badge. */
    private DataObject publicPlayerJson(UUID uuid, String name, LinkedAccount linked) throws SQLException {
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
                    .put("onWebsite", onWebsite(linked.discordId()));
        } else {
            Instant lastActivity = plugin.database().lastActivity(uuid);
            row.put("lastSeen", lastActivity == null ? null : lastActivity.toEpochMilli())
                    .put("isAdmin", false);
        }
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

    private DataArray search(Map<String, String> query, boolean staff) throws SQLException {
        String q = query.getOrDefault("q", "").trim();
        DataArray result = DataArray.empty();
        if (q.isEmpty()) {
            return result;
        }
        for (PlayerRef ref : plugin.database().searchPlayers(q, 20)) {
            LinkedAccount linked = ref.discordId() == null ? null : plugin.database().findByUuid(ref.uuid()).orElse(null);
            result.add(staff
                    ? playerJson(ref.uuid(), ref.name(), linked, plugin.database().activeSuspension(ref.uuid()).orElse(null))
                    : publicPlayerJson(ref.uuid(), ref.name(), linked));
        }
        return result;
    }

    // ---- Community view ----

    /** Everyone who has played: linked members, plus players known from joins and the servers' player data. */
    private DataArray memberList() throws SQLException {
        Map<UUID, KnownPlayer> known = new HashMap<>();
        for (KnownPlayer player : plugin.database().knownPlayers()) {
            known.put(player.uuid(), player);
        }
        Map<UUID, com.huzzysnuzzy.discordlinked.storage.LoginStreak> streaks = plugin.database().loginStreaks();
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.systemDefault());
        DataArray result = DataArray.empty();
        for (LinkedAccount account : plugin.database().allLinked()) {
            KnownPlayer k = known.remove(account.uuid());
            result.add(withStreak(publicPlayerJson(account.uuid(), account.minecraftName(), account)
                    .put("firstPlayed", k == null || k.firstPlayed() == null ? null : k.firstPlayed().toEpochMilli()),
                    streaks.get(account.uuid()), today));
        }
        for (KnownPlayer k : known.values()) {
            // Built from the row itself rather than publicPlayerJson, which would look each one up again.
            Optional<Player> online = plugin.proxy().getPlayer(k.uuid());
            result.add(withStreak(DataObject.empty()
                    .put("uuid", k.uuid().toString())
                    .put("minecraftName", online.map(Player::getUsername).orElse(k.name()))
                    .put("linked", false)
                    .put("online", online.isPresent())
                    .put("server", online.flatMap(Player::getCurrentServer).map(s -> s.getServerInfo().getName()).orElse(""))
                    .put("lastSeen", k.lastPlayed() == null ? null : k.lastPlayed().toEpochMilli())
                    .put("firstPlayed", k.firstPlayed() == null ? null : k.firstPlayed().toEpochMilli())
                    .put("isAdmin", false), streaks.get(k.uuid()), today));
        }
        return result;
    }

    private static DataObject withStreak(DataObject row, com.huzzysnuzzy.discordlinked.storage.LoginStreak streak,
                                         java.time.LocalDate today) {
        return row.put("streak", streak == null ? 0 : streak.currentOn(today)).put("bestStreak", streak == null ? 0 : streak.best());
    }

    /** The daily login streak for a profile: days in a row as of today, the best ever, and today's reward. */
    private DataObject streakJson(UUID uuid) throws SQLException {
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.systemDefault());
        Optional<com.huzzysnuzzy.discordlinked.storage.LoginStreak> streak = plugin.database().loginStreak(uuid);
        int current = streak.map(s -> s.currentOn(today)).orElse(0);
        boolean joinedToday = streak.map(s -> today.equals(s.lastDay())).orElse(false);
        return DataObject.empty()
                .put("current", current)
                .put("best", streak.map(com.huzzysnuzzy.discordlinked.storage.LoginStreak::best).orElse(0))
                .put("joinedToday", joinedToday)
                .put("rewardsOn", plugin.config().fadEnabled() && plugin.community().dailyReward(1) > 0)
                // Today's reward if they joined, otherwise what joining today would pay.
                .put("reward", plugin.community().dailyReward(joinedToday ? current : current + 1))
                .put("maxReward", Math.max(plugin.config().dailyRewardBase(), plugin.config().dailyRewardMax()));
    }

    /** When the player first played, if known. */
    private Long firstPlayed(UUID uuid) throws SQLException {
        return plugin.database().knownPlayer(uuid).map(KnownPlayer::firstPlayed).map(Instant::toEpochMilli).orElse(null);
    }

    private void publicPlayerPage(HttpExchange ex, Map<String, String> query) throws IOException, SQLException {
        UUID uuid = parseUuid(query.get("uuid"));
        Optional<PlayerRef> ref = uuid == null ? Optional.empty() : plugin.database().findPlayer(uuid);
        if (ref.isEmpty()) {
            sendJson(ex, 404, error("No player with that UUID has ever joined"));
            return;
        }
        LinkedAccount linked = plugin.database().findByUuid(uuid).orElse(null);
        // Credits and advancements, without the staff-only parts (the ledger has admins' reasons).
        DataObject community = playerCommunity(uuid, linked);
        community.remove("transactions");
        community.remove("afkSeconds");
        community.remove("optedOut");
        sendJson(ex, 200, DataObject.empty()
                .put("player", publicPlayerJson(uuid, ref.get().name(), linked).put("firstPlayed", firstPlayed(uuid))
                        .put("streak", streakJson(uuid)))
                .put("community", community));
    }

    /** The Home page of the community view: who's online where, the newest members and who was on recently. */
    private DataObject home() throws SQLException {
        List<LinkedAccount> linked = plugin.database().allLinked();
        Map<UUID, LinkedAccount> byUuid = new HashMap<>();
        linked.forEach(account -> byUuid.put(account.uuid(), account));

        DataArray online = DataArray.empty();
        Map<String, Integer> servers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int staffOnline = 0;
        List<Player> players = new ArrayList<>(plugin.proxy().getAllPlayers());
        players.sort(java.util.Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));
        for (Player player : players) {
            DataObject row = publicPlayerJson(player.getUniqueId(), player.getUsername(), byUuid.get(player.getUniqueId()));
            online.add(row);
            if (row.getBoolean("isAdmin", false)) {
                staffOnline++;
            }
            String server = row.getString("server", "");
            if (!server.isEmpty()) {
                servers.merge(server, 1, Integer::sum);
            }
        }
        DataArray serverCounts = DataArray.empty();
        servers.forEach((name, count) -> serverCounts.add(DataObject.empty().put("name", name).put("players", count)));

        DataArray newest = DataArray.empty();
        for (LinkedAccount account : linked.stream()
                .sorted(java.util.Comparator.comparing(LinkedAccount::linkedAt).reversed()).limit(8).toList()) {
            newest.add(publicPlayerJson(account.uuid(), account.minecraftName(), account));
        }
        DataArray recent = DataArray.empty();
        for (LinkedAccount account : linked.stream()
                .filter(a -> a.lastSeen() != null && plugin.proxy().getPlayer(a.uuid()).isEmpty())
                .sorted(java.util.Comparator.comparing(LinkedAccount::lastSeen).reversed()).limit(8).toList()) {
            recent.add(publicPlayerJson(account.uuid(), account.minecraftName(), account));
        }
        return DataObject.empty()
                .put("linkedCount", linked.size())
                .put("playerCount", plugin.database().knownPlayerCount())
                .put("onlineCount", players.size())
                .put("staffOnline", staffOnline)
                .put("servers", serverCounts)
                .put("online", online)
                .put("newest", newest)
                .put("recent", recent);
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
                plugin.database().activeSuspension(uuid).orElse(null)).put("firstPlayed", firstPlayed(uuid))
                .put("streak", streakJson(uuid));

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
                .put("notes", notes).put("ips", ips).put("community", playerCommunity(uuid, linked)));
    }

    // ---- Community: credits and interactions (DiscordLinkedFAD) ----

    private DataObject currency() {
        PluginConfig c = plugin.config();
        return DataObject.empty().put("one", c.creditsName()).put("many", c.creditsNamePlural());
    }

    /** One player's credits, playtime, advancements and interactions, for their page. */
    private DataObject playerCommunity(UUID uuid, LinkedAccount linked) throws SQLException {
        Optional<CreditAccount> account = plugin.database().creditAccount(uuid);
        long[] interactions = plugin.database().interactionTotals(uuid);
        DataArray advancements = DataArray.empty();
        for (EarnedAdvancement a : plugin.database().advancements(uuid)) {
            advancements.add(DataObject.empty().put("key", a.key()).put("title", a.title()).put("frame", a.frame())
                    .put("server", a.server()).put("credits", a.credits()).put("earnedAt", a.earnedAt().toEpochMilli()));
        }
        DataArray transactions = DataArray.empty();
        for (CreditTransaction t : plugin.database().creditTransactions(uuid, 25)) {
            transactions.add(DataObject.empty().put("amount", t.amount()).put("balance", t.balance()).put("kind", t.kind())
                    .put("detail", t.detail() == null ? "" : t.detail()).put("actor", t.actor() == null ? "" : t.actor())
                    .put("time", t.createdAt().toEpochMilli()));
        }
        DataArray favourites = DataArray.empty();
        plugin.database().interactionCounts(uuid).entrySet().stream().limit(6).forEach(e ->
                favourites.add(DataObject.empty().put("action", e.getKey()).put("count", e.getValue())));
        return DataObject.empty()
                .put("enabled", plugin.config().fadEnabled())
                .put("currency", currency())
                .put("credits", account.map(CreditAccount::credits).orElse(0L))
                .put("earned", account.map(CreditAccount::earned).orElse(0L))
                .put("activeSeconds", account.map(CreditAccount::activeSeconds).orElse(0L))
                .put("afkSeconds", account.map(CreditAccount::afkSeconds).orElse(0L))
                .put("rank", plugin.database().creditRank(uuid))
                .put("given", interactions[0])
                .put("received", interactions[1])
                .put("optedOut", plugin.community().isOptedOut(uuid, linked == null ? null : linked.discordId()))
                .put("favourites", favourites)
                .put("advancements", advancements)
                .put("transactions", transactions);
    }

    /** The Community page: totals, leaderboard, recent interactions and the interactions the servers offer. */
    private DataObject community(Map<String, String> query, boolean staff) throws SQLException {
        PluginConfig c = plugin.config();
        String sort = query.getOrDefault("sort", "credits");
        DataArray leaderboard = DataArray.empty();
        for (LeaderboardRow row : plugin.database().leaderboard(sort, 100)) {
            leaderboard.add(DataObject.empty()
                    .put("uuid", row.uuid().toString())
                    .put("name", row.name())
                    .put("credits", row.credits())
                    .put("earned", row.earned())
                    .put("activeSeconds", row.activeSeconds())
                    .put("advancements", row.advancements())
                    .put("given", row.given())
                    .put("received", row.received())
                    .put("online", plugin.proxy().getPlayer(row.uuid()).isPresent()));
        }
        DataArray recent = DataArray.empty();
        for (InteractionRecord i : plugin.database().recentInteractions(40)) {
            recent.add(DataObject.empty()
                    .put("id", i.id())
                    .put("time", i.createdAt().toEpochMilli())
                    .put("action", i.action())
                    .put("actor", i.actorName())
                    .put("actorUuid", i.actorUuid() == null ? null : i.actorUuid().toString())
                    .put("target", i.targetName())
                    .put("targetUuid", i.targetUuid() == null ? null : i.targetUuid().toString())
                    .put("source", i.source())
                    .put("text", i.text()));
        }
        Map<String, Long> uses = plugin.database().interactionCounts(null);
        FadActions.Definitions definitions = plugin.community().actions().current();
        DataArray actions = DataArray.empty();
        if (definitions != null) {
            for (FadActions.Action a : definitions.actions().values()) {
                actions.add(DataObject.empty()
                        .put("name", a.name())
                        .put("description", a.description())
                        .put("target", a.target())
                        .put("lines", a.messages().size() + a.soloMessages().size())
                        .put("uses", uses.getOrDefault(a.name(), 0L)));
            }
        }
        DataArray servers = DataArray.empty();
        if (staff) {
            plugin.community().servers().forEach((name, version) ->
                    servers.add(DataObject.empty().put("name", name).put("version", version)));
        }
        DataObject totals = DataObject.empty();
        plugin.database().communityTotals(Instant.now().minus(Duration.ofDays(1))).forEach(totals::put);
        return DataObject.empty()
                .put("enabled", c.fadEnabled())
                .put("currency", currency())
                .put("rewards", DataObject.empty()
                        .put("playtimeAmount", c.creditsPlaytimeAmount())
                        .put("playtimeMinutes", c.creditsPlaytimeMinutes())
                        .put("task", c.creditsAdvancementTask())
                        .put("goal", c.creditsAdvancementGoal())
                        .put("challenge", c.creditsAdvancementChallenge()))
                .put("discordCommands", c.fadDiscordCommands())
                .put("synced", definitions != null)
                .put("sort", sort)
                .put("totals", totals)
                .put("leaderboard", leaderboard)
                .put("recent", recent)
                .put("actions", actions)
                .put("servers", servers);
    }

    // ---- Shop ----

    /**
     * The Shop page: every item for sale with its price (hundredths of a credit, after the multipliers), the
     * buyer's balance and whether they can receive an order right now. The settings owner also gets the items
     * that are turned off and each item's own percentage.
     */
    private DataObject shop(Sessions.Session session) throws SQLException {
        Shop shop = plugin.community().shop();
        boolean manage = session.staff() && isOwner(session);
        Map<String, ShopOverride> overrides = plugin.database().shopOverrides();
        Set<String> available = shop.availableItems();
        DataArray items = DataArray.empty();
        for (Shop.Item item : shop.catalog().values()) {
            ShopOverride override = overrides.get(item.id());
            boolean disabled = override != null && override.disabled();
            boolean exists = available == null || available.contains(item.id());
            if (!manage && (disabled || !exists)) {
                continue;
            }
            DataObject row = DataObject.empty().put("id", item.id()).put("tab", item.tab()).put("stack", item.stack())
                    .put("price", shop.unitCents(item, override));
            if (manage) {
                row.put("disabled", disabled).put("percent", override == null ? 100 : override.percent())
                        .put("unavailable", !exists);
            }
            items.add(row);
        }
        DataObject buyer = DataObject.empty();
        Optional<LinkedAccount> me = plugin.database().findByDiscordId(session.user.id());
        buyer.put("linked", me.isPresent());
        if (me.isPresent()) {
            Optional<Player> online = plugin.proxy().getPlayer(me.get().uuid());
            String server = online.flatMap(Player::getCurrentServer).map(c -> c.getServerInfo().getName()).orElse("");
            buyer.put("name", me.get().minecraftName())
                    .put("uuid", me.get().uuid().toString())
                    .put("balance", plugin.database().creditAccount(me.get().uuid()).map(CreditAccount::credits).orElse(0L))
                    .put("online", online.isPresent())
                    .put("server", server)
                    .put("serverReady", !server.isEmpty() && plugin.community().servers().containsKey(server));
        }
        // Who a gift can go to: players online right now on a server that can deliver.
        DataArray recipients = DataArray.empty();
        List<Player> online = new ArrayList<>(plugin.proxy().getAllPlayers());
        online.sort(java.util.Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));
        for (Player player : online) {
            String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
            if (me.isPresent() && player.getUniqueId().equals(me.get().uuid())
                    || !plugin.community().servers().containsKey(server)) {
                continue;
            }
            recipients.add(DataObject.empty().put("uuid", player.getUniqueId().toString())
                    .put("name", player.getUsername()).put("server", server));
        }
        return DataObject.empty()
                .put("recipients", recipients)
                .put("open", shop.open())
                .put("fadEnabled", plugin.config().fadEnabled())
                .put("percent", plugin.config().shopPricePercent())
                .put("currency", currency())
                .put("manage", manage)
                .put("slots", Shop.CHEST_SLOTS)
                .put("chestPrice", shop.chestCents(overrides))
                .put("buyer", buyer)
                .put("items", items);
    }

    private void shopCheckout(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        Optional<LinkedAccount> me = plugin.database().findByDiscordId(session.user.id());
        if (me.isEmpty()) {
            sendJson(ex, 409, error("Link your Minecraft account first (/link in Discord)."));
            return;
        }
        DataArray slots = body.optArray("items").orElse(DataArray.empty());
        if (slots.length() > Shop.CHEST_SLOTS) {
            sendJson(ex, 400, error("A chest only has " + Shop.CHEST_SLOTS + " slots."));
            return;
        }
        List<Shop.Line> lines = new ArrayList<>();
        for (int i = 0; i < slots.length(); i++) {
            DataObject slot = slots.getObject(i);
            String id = slot.getString("id", "");
            if (!id.matches("[a-z0-9_]{1,64}")) {
                sendJson(ex, 400, error("Unknown item"));
                return;
            }
            lines.add(new Shop.Line(id, slot.getInt("amount", 0)));
        }
        UUID giftTo = body.getString("giftTo", "").isEmpty() ? null : parseUuid(body.getString("giftTo", ""));
        Shop.Result result = plugin.community().shop().checkout(me.get(), giftTo, lines, session.user.actor() + " (website)");
        sendJson(ex, result.ok() ? 200 : 409, DataObject.empty().put("ok", result.ok()).put("message", result.message())
                .put("cost", result.cost()).put("balance", result.balance())
                .put("error", result.ok() ? null : result.message()));
    }

    /** The settings owner turns one item off or on, or changes its own price percentage. */
    private void shopItem(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        if (!isOwner(session)) {
            sendJson(ex, 403, error("Only the settings owner can change Shop items."));
            return;
        }
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String id = body.getString("id", "");
        if (!plugin.community().shop().catalog().containsKey(id)) {
            sendJson(ex, 400, error("Unknown item"));
            return;
        }
        boolean enabled = body.getBoolean("enabled", true);
        int percent = body.getInt("percent", 100);
        if (percent < 1 || percent > 100_000) {
            sendJson(ex, 400, error("The percentage must be between 1 and 100000."));
            return;
        }
        plugin.database().setShopOverride(id, !enabled, percent);
        plugin.audit().log(null, null, session.user.id(), EventTypes.SETTINGS_CHANGE,
                "shop item " + id + ": " + (enabled ? "for sale" : "not for sale") + ", " + percent + "% price",
                session.user.actor() + " (website)");
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    /** Gives (positive amount) or takes (negative) credits, with a reason. */
    private void adjustCredits(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        Optional<PlayerRef> player = resolvePlayer(parseUuid(body.getString("uuid", "")));
        if (player.isEmpty()) {
            sendJson(ex, 404, error("Unknown player"));
            return;
        }
        long amount = body.getLong("amount", 0);
        if (amount == 0 || Math.abs(amount) > MAX_CREDIT_ADJUSTMENT) {
            sendJson(ex, 400, error("Enter an amount between -" + MAX_CREDIT_ADJUSTMENT + " and " + MAX_CREDIT_ADJUSTMENT
                    + ", other than 0"));
            return;
        }
        String reason = body.getString("reason", "").trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            sendJson(ex, 400, error(reason.isEmpty() ? "A reason is required" : "Reason is too long"));
            return;
        }
        long balance = plugin.community().adjust(player.get(), amount, reason, session.user.actor() + " (website)");
        if (balance < 0) {
            long has = plugin.database().creditAccount(player.get().uuid()).map(CreditAccount::credits).orElse(0L);
            sendJson(ex, 409, error(player.get().name() + " only has " + plugin.config().credits(has)));
            return;
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true).put("balance", balance));
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
            rows.add(logJson(entry));
        }
        DataObject result = DataObject.empty().put("entries", rows);
        result.put("nextBefore", entries.size() == LOG_PAGE_SIZE ? entries.get(entries.size() - 1).id() : null);
        return result;
    }

    private static DataObject logJson(AuditEntry entry) {
        DataArray filters = DataArray.empty();
        LOG_FILTERS.forEach((name, types) -> {
            if (types.contains(entry.eventType())) {
                filters.add(name);
            }
        });
        return DataObject.empty()
                .put("id", entry.id())
                .put("time", entry.timestamp().toEpochMilli())
                .put("type", entry.eventType())
                .put("player", entry.minecraftName() == null ? "" : entry.minecraftName())
                .put("uuid", entry.uuid() == null ? "" : entry.uuid().toString())
                .put("discordId", entry.discordId() == null ? "" : String.valueOf(entry.discordId()))
                .put("actor", entry.actor() == null ? "" : entry.actor())
                .put("detail", entry.detail() == null ? "" : entry.detail())
                .put("filters", filters);
    }

    // ---- Who has the website open ----
    //
    // An open page always has a live-update request waiting (or asks again within seconds), so anyone with a
    // request in the last WEB_ACTIVE has the website open. Changes are pushed like any other live update.

    private static final Duration WEB_ACTIVE = Duration.ofSeconds(45);
    private final Map<Long, Instant> webActive = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService webSweeper;

    private void markOnWebsite(long discordId) {
        if (webActive.put(discordId, Instant.now()) == null) {
            webPresenceChanged(discordId);
        }
    }

    /** Whether this Discord user has the website open right now. */
    public boolean onWebsite(long discordId) {
        Instant last = webActive.get(discordId);
        return last != null && last.isAfter(Instant.now().minus(WEB_ACTIVE));
    }

    private void sweepOnWebsite() {
        Instant cutoff = Instant.now().minus(WEB_ACTIVE);
        for (Map.Entry<Long, Instant> entry : webActive.entrySet()) {
            if (entry.getValue().isBefore(cutoff) && webActive.remove(entry.getKey(), entry.getValue())) {
                webPresenceChanged(entry.getKey());
            }
        }
    }

    /** Tells open pages that this person opened or left the website (as a change to their Minecraft player). */
    private void webPresenceChanged(long discordId) {
        try {
            plugin.database().findByDiscordId(discordId).ifPresent(account -> addLiveEvent(null, "web", account.uuid()));
        } catch (SQLException e) {
            plugin.logger().warn("Could not look up a website visitor", e);
        }
    }

    // ---- Live updates (long polling) ----
    //
    // Each open page asks "anything new since #n?" and the request waits until something happens (or 25s
    // pass). Plain requests like this work through Cloudflare quick tunnels, which can't carry Server-Sent
    // Events, and through any other proxy.

    private static final int LIVE_BUFFER = 1000;
    private static final long POLL_WAIT_MILLIS = 25_000;
    /** Beyond this many waiting pages, polls answer straight away (the page asks again a few seconds later). */
    private static final int MAX_WAITING_POLLS = 150;

    /** A log entry, or another change to a player ({@code kind}, e.g. "snapshot"). */
    private record LiveEvent(long seq, AuditEntry entry, String kind, UUID player) {
    }

    private final Object liveLock = new Object();
    private final ArrayDeque<LiveEvent> liveBuffer = new ArrayDeque<>();
    private long liveSeq;
    private final AtomicInteger waitingPolls = new AtomicInteger();
    private final LiveEvents.Listener liveListener = new LiveEvents.Listener() {
        @Override
        public void onLog(AuditEntry entry) {
            addLiveEvent(entry, null, entry.uuid());
        }

        @Override
        public void onPlayerChange(String kind, UUID player) {
            addLiveEvent(null, kind, player);
        }
    };

    private void addLiveEvent(AuditEntry entry, String kind, UUID player) {
        synchronized (liveLock) {
            liveBuffer.addLast(new LiveEvent(++liveSeq, entry, kind, player));
            while (liveBuffer.size() > LIVE_BUFFER) {
                liveBuffer.removeFirst();
            }
            liveLock.notifyAll();
        }
    }

    /**
     * {@code GET /api/poll?since=n}: the events after #n, waiting for one if there are none yet. Without
     * {@code since} (a page that just opened) it only returns the current number. {@code reset} means events
     * were missed (the page was asleep, or the proxy restarted), so the page should reload what it shows.
     */
    private void poll(HttpExchange ex, Sessions.Session session) throws IOException {
        String sinceParam = query(ex).get("since");
        long since = sinceParam == null ? -1 : parseLong(sinceParam);
        List<LiveEvent> events = new ArrayList<>();
        long seq;
        boolean reset;
        boolean mayWait = waitingPolls.incrementAndGet() <= MAX_WAITING_POLLS;
        try {
            synchronized (liveLock) {
                long deadline = System.currentTimeMillis() + POLL_WAIT_MILLIS;
                while (mayWait && since >= 0 && since == liveSeq && sessions.get(session.token) != null) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    liveLock.wait(left);
                }
                seq = liveSeq;
                long oldest = liveBuffer.isEmpty() ? liveSeq + 1 : liveBuffer.peekFirst().seq();
                reset = since >= 0 && (since > liveSeq || since + 1 < oldest);
                if (since >= 0 && !reset) {
                    for (LiveEvent event : liveBuffer) {
                        if (event.seq() > since) {
                            events.add(event);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            waitingPolls.decrementAndGet();
        }
        if (sessions.get(session.token) == null) {
            sendJson(ex, 401, error("Not logged in"));
            return;
        }
        // Filtered when sent, so going on or off duty applies straight away.
        boolean staff = session.staff();
        DataArray out = DataArray.empty();
        for (LiveEvent event : events) {
            if (staff) {
                out.add(event.entry() != null
                        ? DataObject.empty().put("type", "log").put("data", logJson(event.entry()))
                        : DataObject.empty().put("type", "player").put("kind", event.kind())
                                .put("uuid", event.player().toString()));
            } else if (event.entry() != null && PRESENCE_EVENTS.contains(event.entry().eventType())
                    || "web".equals(event.kind())) {
                // The community view only hears that a player came, went, (un)linked or did something public.
                DataObject presence = DataObject.empty().put("type", "presence");
                if (event.player() != null) {
                    presence.put("uuid", event.player().toString());
                }
                out.add(presence);
            }
        }
        // busy: too many pages are waiting already, so this one didn't wait and should ask again later.
        sendJson(ex, 200, DataObject.empty().put("seq", seq).put("reset", reset).put("busy", !mayWait).put("events", out));
    }

    // ---- Settings (one Discord user only) ----

    private boolean isOwner(Sessions.Session session) {
        long owner = plugin.settingsOwnerId();
        return owner != 0L && owner == session.user.id();
    }

    private void settings(HttpExchange ex, String method, String path, Sessions.Session session) throws IOException, SQLException {
        if (!isOwner(session)) {
            sendJson(ex, 403, error("Only the settings owner can open Settings."));
            return;
        }
        SettingsEditor editor = new SettingsEditor(plugin);
        String actor = session.user.actor() + " (website)";
        switch (path) {
            case "/api/settings/domain" -> sendJson(ex, 200, domainJson());
            case "/api/settings/domain/connect" -> domainConnect(ex, session);
            case "/api/settings/domain/disconnect" -> domainDisconnect(ex, session);
            case "/api/settings/domain/login-link" -> {
                // A one-time login link on the current address, for moving to the new address in one click.
                String link = createLoginLink(session.user.id(), session.user.name(), session.user.avatarUrl());
                sendJson(ex, link == null ? 409 : 200, link == null ? error("The new address isn't live yet.")
                        : DataObject.empty().put("link", link));
            }
            case "/api/settings" -> {
                if (method.equals("POST")) {
                    DataObject body = readJson(ex);
                    if (body == null) {
                        return;
                    }
                    Map<String, String> changes = new java.util.LinkedHashMap<>();
                    DataObject requested = body.optObject("changes").orElse(DataObject.empty());
                    for (String key : requested.keys()) {
                        changes.put(key, String.valueOf(requested.get(key)));
                    }
                    SettingsEditor.Result result;
                    try {
                        result = editor.apply(changes, actor);
                    } catch (IllegalArgumentException e) {
                        sendJson(ex, 400, error(e.getMessage()));
                        return;
                    }
                    sendJson(ex, 200, DataObject.empty().put("ok", true)
                            .put("changed", DataArray.fromCollection(result.changed()))
                            .put("needRestart", DataArray.fromCollection(result.needRestart())));
                    return;
                }
                Map<String, String> values = editor.values();
                DataArray groups = DataArray.empty();
                Map<String, DataArray> byGroup = new java.util.LinkedHashMap<>();
                for (SettingsEditor.Field field : SettingsEditor.FIELDS) {
                    byGroup.computeIfAbsent(field.group(), g -> DataArray.empty()).add(DataObject.empty()
                            .put("key", field.key())
                            .put("label", field.label())
                            .put("help", field.help())
                            .put("type", field.type().name().toLowerCase())
                            .put("restart", field.restart())
                            .put("min", field.min())
                            .put("max", field.max())
                            .put("choices", DataArray.fromCollection(field.choices()))
                            .put("value", values.get(field.key())));
                }
                byGroup.forEach((name, fields) -> groups.add(DataObject.empty().put("name", name).put("fields", fields)));
                DataObject secrets = DataObject.empty();
                editor.secrets().forEach(secrets::put);
                sendJson(ex, 200, DataObject.empty()
                        .put("groups", groups)
                        .put("secrets", secrets)
                        .put("ownerIsDefault", plugin.config().webOwnerId() == 0L)
                        .put("data", dataStats()));
            }
            case "/api/settings/cleanup" -> {
                String summary = plugin.runMaintenance();
                plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "ran cleanup: " + summary, actor);
                sendJson(ex, 200, DataObject.empty().put("summary", summary).put("data", dataStats()));
            }
            case "/api/settings/compact" -> {
                long before = databaseBytes();
                plugin.database().compact();
                plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "compacted the database", actor);
                sendJson(ex, 200, DataObject.empty().put("freed", Math.max(0, before - databaseBytes())).put("data", dataStats()));
            }
            default -> sendJson(ex, 404, error("Unknown endpoint"));
        }
    }

    private long databaseBytes() {
        long total = 0;
        for (String name : List.of("data.db", "data.db-wal")) {
            try {
                total += java.nio.file.Files.size(plugin.dataDirectory().resolve(name));
            } catch (IOException ignored) {
                // file not there
            }
        }
        return total;
    }

    private DataObject dataStats() throws SQLException {
        DataObject stats = DataObject.empty().put("databaseBytes", databaseBytes());
        plugin.database().counts().forEach(stats::put);
        return stats;
    }

    // ---- Inventory snapshots ----

    private DataObject snapshotJson(InventorySnapshotRecord s) {
        return DataObject.empty()
                .put("id", s.id())
                .put("reason", s.reason())
                .put("detail", s.detail() == null ? "" : s.detail())
                .put("server", s.server())
                .put("createdAt", s.createdAt().toEpochMilli())
                .put("stacks", s.stacks());
    }

    private void snapshots(HttpExchange ex, Map<String, String> query) throws IOException, SQLException {
        UUID uuid = parseUuid(query.get("uuid"));
        if (uuid == null) {
            sendJson(ex, 400, error("Invalid player"));
            return;
        }
        DataArray list = DataArray.empty();
        for (InventorySnapshotRecord s : plugin.database().snapshots(uuid)) {
            list.add(snapshotJson(s));
        }
        Optional<PendingRestore> pending = plugin.database().pendingRestore(uuid);
        sendJson(ex, 200, DataObject.empty().put("snapshots", list).put("pendingRestore", pending.map(p -> DataObject.empty()
                .put("snapshotId", p.snapshotId()).put("actor", p.actor()).put("reason", p.reason())).orElse(null)));
    }

    private void snapshotPreview(HttpExchange ex, Map<String, String> query) throws IOException, SQLException {
        Optional<InventorySnapshotRecord> snapshot = plugin.database().snapshot(parseLong(query.get("id")));
        if (snapshot.isEmpty()) {
            sendJson(ex, 404, error("That snapshot no longer exists"));
            return;
        }
        DataObject display;
        try {
            display = DataObject.fromJson(snapshot.get().display());
        } catch (ParsingException e) {
            sendJson(ex, 500, error("This snapshot can't be displayed"));
            return;
        }
        sendJson(ex, 200, snapshotJson(snapshot.get()).put("inventory", display.put("server", snapshot.get().server())));
    }

    private void snapshotSave(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        UUID uuid = parseUuid(body.getString("uuid", ""));
        Optional<Player> player = uuid == null ? Optional.empty() : plugin.proxy().getPlayer(uuid);
        if (player.isEmpty() || !plugin.inventories().snapshotNow(player.get(), session.user.actor() + " (website)")) {
            sendJson(ex, 409, error("Snapshots can only be taken while the player is online"));
            return;
        }
        Optional<PlayerRef> ref = resolvePlayer(uuid);
        plugin.audit().log(uuid, player.get().getUsername(), ref.map(PlayerRef::discordId).orElse(null),
                EventTypes.INVENTORY_SNAPSHOT, "saved a snapshot", session.user.actor() + " (website)");
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    private void snapshotRestore(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        Optional<InventorySnapshotRecord> found = plugin.database().snapshot(body.getLong("id", 0));
        if (found.isEmpty()) {
            sendJson(ex, 404, error("That snapshot no longer exists"));
            return;
        }
        String reason = body.getString("reason", "").trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            sendJson(ex, 400, error(reason.isEmpty() ? "A reason is required" : "Reason is too long"));
            return;
        }
        boolean includeEnder = body.getBoolean("includeEnderChest", false);
        InventorySnapshotRecord snapshot = found.get();
        String actor = session.user.actor() + " (website)";
        Long discordId = resolvePlayer(snapshot.uuid()).map(PlayerRef::discordId).orElse(null);
        String what = InventoryRequests.describe(snapshot) + (includeEnder ? " incl. ender chest" : "");
        Optional<Player> player = plugin.proxy().getPlayer(snapshot.uuid());
        if (player.isEmpty()) {
            plugin.database().clearPendingRestores(snapshot.uuid());
            plugin.database().addPendingRestore(snapshot.uuid(), snapshot.id(), includeEnder, actor, reason);
            plugin.audit().log(snapshot.uuid(), snapshot.minecraftName(), discordId, EventTypes.INVENTORY_RESTORE,
                    what + ", queued until they next join: " + reason, actor);
            sendJson(ex, 200, DataObject.empty().put("ok", true).put("queued", true));
            return;
        }
        String outcome;
        try {
            outcome = plugin.inventories().restore(player.get(), snapshot, includeEnder).get(12, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            outcome = "no reply from the server. Is the DiscordLinkedSpy plugin installed there?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
        }
        boolean ok = outcome.equals("ok");
        plugin.audit().log(snapshot.uuid(), snapshot.minecraftName(), discordId, EventTypes.INVENTORY_RESTORE,
                what + (ok ? ", applied: " : ", FAILED (" + outcome + "): ") + reason, actor);
        if (!ok) {
            sendJson(ex, 502, error("Restore failed: " + outcome));
            return;
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true).put("queued", false));
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

    // ---- Inventory viewer ----

    private void inventory(HttpExchange ex, Map<String, String> query, Sessions.Session session)
            throws IOException, SQLException {
        UUID uuid = parseUuid(query.get("uuid"));
        Optional<Player> player = uuid == null ? Optional.empty() : plugin.proxy().getPlayer(uuid);
        if (player.isEmpty()) {
            sendJson(ex, 409, error("The inventory can only be viewed while the player is online."));
            return;
        }
        String server = player.get().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("their server");
        DataObject snapshot;
        try {
            snapshot = DataObject.fromJson(plugin.inventories().request(player.get()).get(6, TimeUnit.SECONDS));
        } catch (TimeoutException | ExecutionException e) {
            sendJson(ex, 504, error("No reply from " + server + ". Is the DiscordLinkedSpy plugin installed there?"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 503, error("Interrupted"));
            return;
        } catch (ParsingException e) {
            sendJson(ex, 502, error("Got an unreadable inventory from " + server));
            return;
        }
        // Only log opening the viewer, not every live refresh while it stays open.
        if (!"1".equals(query.get("live"))) {
            Optional<PlayerRef> ref = resolvePlayer(uuid);
            plugin.audit().log(uuid, ref.map(PlayerRef::name).orElse(player.get().getUsername()),
                    ref.map(PlayerRef::discordId).orElse(null), EventTypes.INVENTORY_VIEW, "on " + server, session.user.actor());
        }
        sendJson(ex, 200, snapshot.put("server", server));
    }

    // ---- Moderate page & spy ----

    private DataObject onlineList(Sessions.Session session) throws SQLException {
        Optional<LinkedAccount> self = plugin.database().findByDiscordId(session.user.id());
        Map<UUID, Suspension> suspensions = plugin.database().activeSuspensions();
        DataArray players = DataArray.empty();
        for (Player player : plugin.proxy().getAllPlayers()) {
            LinkedAccount linked = plugin.database().findByUuid(player.getUniqueId()).orElse(null);
            players.add(playerJson(player.getUniqueId(), player.getUsername(), linked, suspensions.get(player.getUniqueId()))
                    .put("ping", player.getPing())
                    .put("self", self.isPresent() && self.get().uuid().equals(player.getUniqueId())));
        }
        String selfStatus = self.isEmpty() ? "unlinked"
                : plugin.proxy().getPlayer(self.get().uuid()).isPresent() ? "online" : "offline";
        return DataObject.empty().put("players", players).put("selfStatus", selfStatus)
                .put("selfName", self.map(LinkedAccount::minecraftName).orElse(null))
                .put("maxDistance", plugin.config().spyMaxDistance());
    }

    private DataObject spyStatus(Sessions.Session session) throws SQLException {
        DataObject result = DataObject.empty();
        SpyManager.Session spy = plugin.spy().current(session.user.id());
        if (spy == null) {
            result.putNull("session");
        } else {
            LinkedAccount linked = plugin.database().findByUuid(spy.target.uuid()).orElse(null);
            result.put("session", DataObject.empty()
                    .put("state", spy.state.name().toLowerCase())
                    .put("server", spy.serverName)
                    .put("seconds", spy.elapsed().toSeconds())
                    .put("distance", spy.distance)
                    .put("maxDistance", plugin.config().spyMaxDistance())
                    .put("target", playerJson(spy.target.uuid(), spy.target.name(), linked,
                            plugin.database().activeSuspension(spy.target.uuid()).orElse(null))));
        }
        SpyManager.Ended ended = plugin.spy().takeEnded(session.user.id());
        result.put("ended", ended == null ? null : DataObject.empty()
                .put("player", ended.targetName())
                .put("uuid", ended.targetUuid().toString())
                .put("reason", ended.reason())
                .put("duration", TimeFormat.preciseDuration(ended.duration()))
                .put("failed", ended.failed()));
        return result;
    }

    /** Teleports the admin's own character to a player's location (logout spot if offline) or bed. */
    private void teleport(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        String mode = body.getString("mode", "");
        if (!mode.equals("player") && !mode.equals("bed")) {
            sendJson(ex, 400, error("Unknown teleport"));
            return;
        }
        Optional<LinkedAccount> self = plugin.database().findByDiscordId(session.user.id());
        if (self.isEmpty()) {
            sendJson(ex, 409, error("Your Discord account isn't linked to a Minecraft account. Link it and join the server first."));
            return;
        }
        Optional<Player> admin = plugin.proxy().getPlayer(self.get().uuid());
        if (admin.isEmpty()) {
            sendJson(ex, 409, error("Join the Minecraft server as " + self.get().minecraftName() + " first."));
            return;
        }
        Optional<PlayerRef> target = resolvePlayer(parseUuid(body.getString("uuid", "")));
        if (target.isEmpty()) {
            sendJson(ex, 404, error("Unknown player"));
            return;
        }
        if (target.get().uuid().equals(self.get().uuid())) {
            sendJson(ex, 409, error("That's you."));
            return;
        }
        boolean online = plugin.proxy().getPlayer(target.get().uuid()).isPresent();
        String problem = plugin.spy().teleport(admin.get(), target.get(), mode);
        if (problem != null) {
            sendJson(ex, 409, error(problem));
            return;
        }
        String place = mode.equals("bed") ? "bed / respawn point" : online ? "location" : "logout spot";
        String server = admin.get().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?");
        plugin.audit().log(target.get().uuid(), target.get().name(), target.get().discordId(), EventTypes.TELEPORT,
                "teleported " + self.get().minecraftName() + " to their " + place + " on " + server,
                session.user.actor() + " (website)");
        admin.get().sendMessage(plugin.messages().minecraft("minecraft.teleport.done",
                "player", target.get().name(), "place", place));
        sendJson(ex, 200, DataObject.empty().put("ok", true).put("message", "Teleported to " + target.get().name() + "'s " + place + "."));
    }

    private void spyStart(HttpExchange ex, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        Optional<LinkedAccount> self = plugin.database().findByDiscordId(session.user.id());
        if (self.isEmpty()) {
            sendJson(ex, 409, error("Your Discord account isn't linked to a Minecraft account. Link it and join the server first."));
            return;
        }
        Optional<Player> admin = plugin.proxy().getPlayer(self.get().uuid());
        if (admin.isEmpty()) {
            sendJson(ex, 409, error("Join the Minecraft server as " + self.get().minecraftName() + " first, then start spying."));
            return;
        }
        UUID uuid = parseUuid(body.getString("uuid", ""));
        Optional<Player> target = uuid == null ? Optional.empty() : plugin.proxy().getPlayer(uuid);
        Optional<PlayerRef> targetRef = resolvePlayer(uuid);
        if (target.isEmpty() || targetRef.isEmpty()) {
            sendJson(ex, 409, error("That player isn't online"));
            return;
        }
        String problem = plugin.spy().start(session.user.id(), session.user.actor() + " (website)",
                admin.get(), target.get(), targetRef.get());
        if (problem != null) {
            sendJson(ex, 409, error(problem));
            return;
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    /** End, or end-and-kick / end-and-suspend the player being spied on. */
    private void spyAction(HttpExchange ex, String path, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        SpyManager.Session spy = plugin.spy().current(session.user.id());
        if (spy == null) {
            sendJson(ex, 409, error("No spy session is running"));
            return;
        }
        if (path.equals("/api/spy/end")) {
            plugin.spy().end(spy, "ended from the website");
            sendJson(ex, 200, DataObject.empty().put("ok", true));
            return;
        }
        String reason = body.getString("reason", "").trim();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            sendJson(ex, 400, error(reason.isEmpty() ? "A reason is required" : "Reason is too long"));
            return;
        }
        Duration duration = null;
        if (path.equals("/api/spy/suspend")) {
            duration = parseDuration(body);
            if (duration == null) {
                sendJson(ex, 400, error("Enter a duration of at least 1 hour"));
                return;
            }
            if (plugin.database().activeSuspension(spy.target.uuid()).isPresent()) {
                sendJson(ex, 409, error("Player is already suspended"));
                return;
            }
        }
        String fullReason = reason + " (observed via spy for " + TimeFormat.preciseDuration(spy.elapsed()) + ")";
        String actor = session.user.actor() + " (website)";
        if (duration == null) {
            plugin.spy().end(spy, "you kicked the player");
            plugin.kick(spy.target, fullReason, actor);
        } else {
            plugin.spy().end(spy, "you suspended the player");
            plugin.suspend(spy.target, duration, fullReason, actor);
        }
        sendJson(ex, 200, DataObject.empty().put("ok", true));
    }

    private void handleAction(HttpExchange ex, String path, Sessions.Session session) throws IOException, SQLException {
        DataObject body = readJson(ex);
        if (body == null) {
            return;
        }
        UUID uuid = parseUuid(body.getString("uuid", ""));
        Optional<PlayerRef> player = resolvePlayer(uuid);
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

    /** A known player, or anyone online right now (even if nothing about them is stored yet). */
    private Optional<PlayerRef> resolvePlayer(UUID uuid) throws SQLException {
        if (uuid == null) {
            return Optional.empty();
        }
        Optional<PlayerRef> known = plugin.database().findPlayer(uuid);
        if (known.isPresent()) {
            return known;
        }
        return plugin.proxy().getPlayer(uuid).map(p -> new PlayerRef(uuid, p.getUsername(), null));
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
                + "https://cdn.discordapp.com https://cdn.jsdelivr.net; connect-src 'self' https://cdn.jsdelivr.net; "
                + "style-src 'self'; script-src 'self'; frame-ancestors 'none'; "
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
