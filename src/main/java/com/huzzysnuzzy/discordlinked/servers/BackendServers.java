package com.huzzysnuzzy.discordlinked.servers;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.SettingsEditor;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every backend server behind the proxy: the ones registered in Velocity, plus any the database remembers (so a
 * server's switches survive it being out of Velocity's config for a while). For each it knows whether it's up
 * (pinged every 30 seconds), what its plugin reported (HELLO, and STATUS from DiscordLinkedBackend) and the settings
 * owner's switches for earning credits and microcredits there, and for maintenance mode (only admins can join; see
 * {@link Maintenance}). Names are matched ignoring case, like Velocity does.
 *
 * <p>Plugin messages only travel through a player, so a server nobody is on can't send STATUS. The pings therefore
 * carry {@link #PROBE_HOST} as their host name, and DiscordLinkedBackend 0.22.1 and newer answers those with its
 * status in the MOTD, which counts as a STATUS. That needs Velocity 3.4.0 or newer (3.4.0-SNAPSHOT builds since
 * February 2025); older ones ping as before.
 */
public final class BackendServers {

    public static final String PLUGIN = "DiscordLinkedBackend";
    /** A server that says HELLO (older than 0.22.0) but no STATUS runs this, from before DiscordLinkedBackend. */
    public static final String OLD_PLUGIN = "DiscordLinkedFAD";
    /**
     * The host name the pings carry, so DiscordLinkedBackend answers with its status (StatusPing in the backend
     * plugin, which has the same name). ".invalid" is reserved and never resolves, so no player connects with it by
     * accident. Velocity's ping passthrough sends the client's own host name to the servers, so someone who points
     * that name at the proxy on purpose would get the status back: {@link #onProxyPing} swaps it for the proxy's MOTD.
     */
    public static final String PROBE_HOST = "discordlinked-status.invalid";
    /** The status answer is about 200 characters; a MOTD much longer than that isn't one. */
    private static final int MAX_ANSWER_CHARS = 4096;
    /** DiscordLinkedFAD had the project's version and ended with 0.21.x; from 0.22.0 on, it's DiscordLinkedBackend. */
    private static final int[] FIRST_BACKEND_VERSION = {0, 22, 0};
    private static final long PING_SECONDS = 30;
    private static final long PING_TIMEOUT_SECONDS = 4;
    /** How long the website waits for fresh pings before it shows what it has. */
    private static final long REFRESH_WAIT_MILLIS = 1500;
    /** DiscordLinkedBackend sends STATUS straight after HELLO_OK; a HELLO with no STATUS by then is the old plugin. */
    private static final long STATUS_WAIT_SECONDS = 10;
    /** TPS and MSPT are shown while STATUS keeps coming (every 30 seconds while someone is on the server). */
    private static final long STATUS_FRESH_MILLIS = 90_000;
    /**
     * The website uses the last-seen time in memory; the stored one only matters after a proxy restart. So with
     * nothing else changed, a status (every 30 seconds per server, through its ping) writes it this often at most.
     */
    private static final long SEEN_SAVE_MILLIS = 5 * 60_000;
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");
    private static final Pattern VERSION_PARTS = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    /** The longest message for players turned away from a server in maintenance. */
    public static final int MAX_MAINTENANCE_MESSAGE = 200;

    /** DiscordLinkedBackend's STATUS: its name and version, and how the server is doing. -1 = not known. */
    public record Status(String plugin, String version, String minecraftVersion, boolean folia, int online, int max,
                         double tps, double mspt, Boolean spy) {
    }

    /**
     * One server as the website shows it. Null means not known: {@code reachable} before the first ping, the
     * plugin fields if it never said hello, {@code tps} and the like without a recent STATUS.
     *
     * @param registered whether it's in Velocity's config right now (otherwise only remembered)
     * @param players    who's on it through this proxy right now (a ping can be 30 seconds old)
     * @param maxPlayers its slots, from the last ping or STATUS
     * @param lastSeen   when its plugin last said something
     * @param answered   whether the last ping got DiscordLinkedBackend's status back: false if the server answered
     *                   without it, null if it didn't answer, hasn't been pinged yet or pings can't ask (old Velocity)
     * @param maintenanceMessage what players turned away during maintenance are told ("" = the default message)
     */
    public record Server(String name, boolean registered, Boolean reachable, Instant checkedAt, int players,
                         Integer maxPlayers, String minecraftVersion, String pluginName, String pluginVersion,
                         Boolean folia, Boolean spy, Double tps, Double mspt, Instant lastSeen, Boolean answered,
                         boolean creditsEnabled, boolean microEnabled, boolean maintenance,
                         String maintenanceMessage) {

        /** It runs DiscordLinkedFAD from before the merge, which should be replaced with DiscordLinkedBackend. */
        public boolean oldPlugin() {
            return OLD_PLUGIN.equals(pluginName);
        }
    }

    private record Ping(boolean reachable, int max, long at) {
    }

    /** What's known about one server. Updated from the network threads, so every field stands on its own. */
    private static final class Entry {
        volatile String name;
        volatile boolean credits = true;
        volatile boolean micro = true;
        volatile boolean maintenance;
        /** For players turned away during maintenance; "" = the default from messages.properties. */
        volatile String maintenanceMessage = "";
        volatile String pluginName;
        volatile String pluginVersion;
        volatile String minecraftVersion;
        volatile long lastSeen;
        /** {@code lastSeen} as last written to the database (see {@link #SEEN_SAVE_MILLIS}). */
        volatile long savedSeen;
        /** The last HELLO since the proxy started (0 = none) and the version it gave. */
        volatile long helloAt;
        volatile String helloVersion;
        volatile Status status;
        volatile long statusAt;
        volatile Ping ping;
        /** Whether the last ping got the status back (see {@link Server#answered()}). */
        volatile Boolean answered;

        Entry(String name) {
            this.name = name;
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    private final DiscordLinked plugin;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastRefresh;
    /** False once this Velocity turned out too old to put {@link #PROBE_HOST} in a ping, for the rest of the run. */
    private final AtomicBoolean probeSupported = new AtomicBoolean(true);

    public BackendServers(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Loads the remembered servers, adds Velocity's and starts pinging them. */
    public void start() {
        try {
            for (Database.BackendServerRow row : plugin.database().backendServers()) {
                Entry entry = new Entry(row.name());
                entry.credits = row.creditsEnabled();
                entry.micro = row.microEnabled();
                entry.maintenance = row.maintenance();
                entry.maintenanceMessage = row.maintenanceMessage() == null ? "" : row.maintenanceMessage();
                entry.pluginName = row.pluginName();
                entry.pluginVersion = row.pluginVersion();
                entry.minecraftVersion = row.minecraftVersion();
                entry.lastSeen = row.lastSeen() == null ? 0 : row.lastSeen().toEpochMilli();
                entry.savedSeen = entry.lastSeen;
                if (OLD_PLUGIN.equals(entry.pluginName) && atLeast(entry.pluginVersion, FIRST_BACKEND_VERSION)) {
                    // The 0.22.0 proxy stored this for a DiscordLinkedBackend 0.22.0 whose STATUS went missing after
                    // its HELLO. DiscordLinkedFAD ended with 0.21.x, so it can only be DiscordLinkedBackend.
                    entry.pluginName = PLUGIN;
                    String version = entry.pluginVersion;
                    save(() -> plugin.database().setBackendPlugin(row.name(), PLUGIN, version, null, null));
                }
                entries.put(key(row.name()), entry);
            }
        } catch (SQLException e) {
            plugin.logger().warn("Could not load the backend servers", e);
        }
        registered();
        plugin.proxy().getScheduler().buildTask(plugin, this::refresh)
                .repeat(PING_SECONDS, TimeUnit.SECONDS).schedule();
    }

    // ---- Looking servers up ----

    /** Every server, by name (A-Z). */
    public List<Server> list() {
        Set<String> registered = registered();
        Map<String, Integer> players = new HashMap<>();
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.getCurrentServer().ifPresent(c -> players.merge(key(c.getServerInfo().getName()), 1, Integer::sum));
        }
        long now = System.currentTimeMillis();
        List<Server> list = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            list.add(view(entry.getValue(), registered.contains(entry.getKey()),
                    players.getOrDefault(entry.getKey(), 0), now));
        }
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        return list;
    }

    /** Every server's name (A-Z). */
    public List<String> names() {
        return list().stream().map(Server::name).toList();
    }

    /** The server's name as Velocity (or the database) spells it, or null if there's no such server. */
    public String name(String server) {
        registered();
        Entry entry = server == null ? null : entries.get(key(server));
        return entry == null ? null : entry.name;
    }

    /** Whether players earn credits on this server (playtime and advancements). Unknown servers do. */
    public boolean creditsEnabled(String server) {
        Entry entry = entries.get(key(server));
        return entry == null || entry.credits;
    }

    /** Whether players can sell for microcredits on this server. Unknown servers can. */
    public boolean microEnabled(String server) {
        Entry entry = entries.get(key(server));
        return entry == null || entry.micro;
    }

    /** Whether only admins can join this server right now (see {@link Maintenance}). Unknown servers aren't. */
    public boolean inMaintenance(String server) {
        Entry entry = server == null ? null : entries.get(key(server));
        return entry != null && entry.maintenance;
    }

    /** Whether the server's last ping (it's in Velocity's config) got no answer, so connecting there would fail. */
    public boolean unreachable(String server) {
        Entry entry = server == null ? null : entries.get(key(server));
        Ping ping = entry == null ? null : entry.ping;
        return ping != null && !ping.reachable();
    }

    /** What players turned away from this server are told; "" = the default message. */
    public String maintenanceMessage(String server) {
        Entry entry = server == null ? null : entries.get(key(server));
        return entry == null ? "" : entry.maintenanceMessage;
    }

    /** The servers in Velocity's config that are in maintenance (A-Z), as spelt. */
    public List<String> inMaintenance() {
        return list().stream().filter(s -> s.maintenance() && s.registered()).map(Server::name).toList();
    }

    /** Whether the pings ask DiscordLinkedBackend for its status; false on Velocity older than 3.4.0. */
    public boolean probing() {
        return probeSupported.get();
    }

    /**
     * Servers where the backend plugin is running, with its version (A-Z): ones whose last ping got
     * DiscordLinkedBackend's status, so also servers nobody is on, and ones whose plugin said hello since the proxy
     * started. What the website shows; deliveries and the like go by {@link #withPlugin()}, which needs a HELLO.
     */
    public Map<String, String> running() {
        Set<String> registered = registered();
        Map<String, String> servers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (registered.contains(e.getKey()) && Boolean.TRUE.equals(entry.answered) && entry.pluginName != null) {
                servers.put(entry.name, entry.pluginVersion);
            } else if (entry.helloAt > 0) {
                servers.put(entry.name, entry.helloVersion);
            }
        }
        return servers;
    }

    /** Servers whose plugin said hello since the proxy started, with the version it gave. */
    public Map<String, String> withPlugin() {
        Map<String, String> servers = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.helloAt > 0) {
                servers.put(entry.name, entry.helloVersion);
            }
        }
        return servers;
    }

    /**
     * The server the website's server pickers start on, or "" for all servers (also while it's not in Velocity's
     * config: nobody can be on it then, and the setting comes back into use if the server does).
     */
    public String defaultServer() {
        String server = plugin.config().defaultServer();
        if (server.isEmpty()) {
            return "";
        }
        String name = name(server);
        return name == null || !registered().contains(key(name)) ? "" : name;
    }

    // ---- From the backend plugin (discordlinked:fad) ----

    /**
     * A server's plugin said hello. DiscordLinkedBackend follows up with STATUS right away; if none comes, it's
     * DiscordLinkedFAD from before the merge. A HELLO from 0.22.0 or newer can only be DiscordLinkedBackend (the old
     * plugin ended with 0.21.x), so that's noted straight away: its STATUS can go missing when the player leaves
     * during the exchange, and that's no reason to call it old.
     */
    public void hello(String server, String version) {
        Entry entry = entry(server);
        long now = System.currentTimeMillis();
        entry.helloAt = now;
        entry.helloVersion = version;
        entry.lastSeen = now;
        entry.savedSeen = now;
        boolean backend = atLeast(version, FIRST_BACKEND_VERSION);
        if (backend) {
            entry.pluginName = PLUGIN;
            entry.pluginVersion = version;
        }
        save(() -> plugin.database().setBackendPlugin(entry.name, backend ? PLUGIN : null, backend ? version : null,
                null, Instant.ofEpochMilli(now)));
        if (backend) {
            return;
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (entry.helloAt == now && entry.statusAt < now) {
                entry.pluginName = OLD_PLUGIN;
                entry.pluginVersion = version;
                save(() -> plugin.database().setBackendPlugin(entry.name, OLD_PLUGIN, version, null, null));
            }
        }).delay(STATUS_WAIT_SECONDS, TimeUnit.SECONDS).schedule();
    }

    /**
     * DiscordLinkedBackend's regular STATUS (after HELLO, then every 30 seconds while someone is on the server). Its
     * answer to a ping (every 30 seconds, also with nobody on) counts the same.
     */
    public void status(String server, Status status) {
        status(entry(server), status);
    }

    private void status(Entry entry, Status status) {
        long now = System.currentTimeMillis();
        String minecraft = status.minecraftVersion().isEmpty() ? entry.minecraftVersion : status.minecraftVersion();
        boolean changed = !status.plugin().equals(entry.pluginName) || !status.version().equals(entry.pluginVersion)
                || !Objects.equals(minecraft, entry.minecraftVersion);
        entry.status = status;
        entry.statusAt = now;
        entry.lastSeen = now;
        entry.pluginName = status.plugin();
        entry.pluginVersion = status.version();
        entry.minecraftVersion = minecraft;
        if (changed || now - entry.savedSeen >= SEEN_SAVE_MILLIS) {
            entry.savedSeen = now;
            save(() -> plugin.database().setBackendPlugin(entry.name, status.plugin(), status.version(), minecraft,
                    Instant.ofEpochMilli(now)));
        }
    }

    // ---- Pinging ----

    /** Pings every server in Velocity's config. Completes when all have answered or timed out. */
    public CompletableFuture<Void> refresh() {
        lastRefresh = System.currentTimeMillis();
        List<CompletableFuture<Void>> pings = new ArrayList<>();
        for (RegisteredServer server : plugin.proxy().getAllServers()) {
            pings.add(ping(entry(server.getServerInfo().getName()), server));
        }
        return CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new));
    }

    /**
     * Pings one server with {@link #PROBE_HOST}, so DiscordLinkedBackend answers with its status. If that gets no
     * answer, a plain ping says whether the server is up at all (a plugin there may turn away unknown host names).
     */
    private CompletableFuture<Void> ping(Entry entry, RegisteredServer server) {
        CompletableFuture<ServerPing> probe = probe(server);
        if (probe == null) {
            return plainPing(entry, server);
        }
        return probe.orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((answer, error) -> error == null ? answer : null)
                .thenCompose(answer -> {
                    if (answer == null) {
                        return plainPing(entry, server);
                    }
                    pinged(entry, answer, true);
                    return CompletableFuture.<Void>completedFuture(null);
                });
    }

    private CompletableFuture<Void> plainPing(Entry entry, RegisteredServer server) {
        CompletableFuture<ServerPing> ping;
        try {
            ping = server.ping();
        } catch (RuntimeException e) {
            ping = CompletableFuture.failedFuture(e);
        }
        return ping.orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).handle((answer, error) -> {
            pinged(entry, error == null ? answer : null, false);
            return null;
        });
    }

    /** The ping with {@link #PROBE_HOST}, or null where this Velocity can't send one. */
    private CompletableFuture<ServerPing> probe(RegisteredServer server) {
        if (!probeSupported.get()) {
            return null;
        }
        try {
            return Probe.ping(server);
        } catch (LinkageError e) {
            // PingOptions.Builder#virtualHost came with Velocity 3.4.0, in its 3.4.0-SNAPSHOT builds from 2025-01-31 on
            // (before 3.2.0 there's no PingOptions at all).
            if (probeSupported.compareAndSet(true, false)) {
                plugin.logger().info("This Velocity build can't put a host name in its pings, so they can't ask "
                        + "DiscordLinkedBackend how a server is doing: servers' plugins are only identified once a "
                        + "player joins them. Update Velocity to its latest build (3.4.0 or newer, or a 3.4.0-SNAPSHOT "
                        + "from February 2025 or later) to have them identified with nobody on.");
            }
            return null;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Kept apart, so a Velocity without the API fails only here (as a LinkageError) and not BackendServers itself. */
    private static final class Probe {
        static CompletableFuture<ServerPing> ping(RegisteredServer server) {
            return server.ping(PingOptions.builder().virtualHost(PROBE_HOST)
                    .timeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).build());
        }
    }

    /**
     * With Velocity's ping passthrough on, the servers are pinged with the client's own host name, so someone who
     * points {@link #PROBE_HOST} at the proxy would get DiscordLinkedBackend's status as the MOTD. They get the
     * proxy's own MOTD instead, as when passthrough finds nothing. First, so MOTD plugins see the swapped one.
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public void onProxyPing(ProxyPingEvent event) {
        try {
            ServerPing ping = event.getPing();
            if (reported(ping) != null) {
                event.setPing(ping.asBuilder().description(plugin.proxy().getConfiguration().getMotd()).build());
            }
        } catch (RuntimeException e) {
            plugin.logger().debug("Could not check a ping's MOTD", e);
        }
    }

    /** For the website: fresh pings unless there were some a moment ago, waiting a little for the answers. */
    public void refreshSoon() {
        if (System.currentTimeMillis() - lastRefresh < 5_000) {
            return;
        }
        try {
            refresh().get(REFRESH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // shown as they were; the late answers still arrive
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A ping's answer (null = none). {@code probed}: it carried {@link #PROBE_HOST}. */
    private void pinged(Entry entry, ServerPing answer, boolean probed) {
        long now = System.currentTimeMillis();
        if (answer == null) {
            entry.ping = new Ping(false, 0, now);
            entry.answered = null;
            return;
        }
        entry.ping = new Ping(true, answer.getPlayers().map(ServerPing.Players::getMax).orElse(-1), now);
        Status reported = probed ? reported(answer) : null;
        entry.answered = probed ? Boolean.valueOf(reported != null) : null;
        if (reported != null) {
            status(entry, reported);
        }
        // Without a recent STATUS, the version the server tells pinging clients ("Paper 1.21.4") is the best there is.
        Status status = entry.status;
        if (status == null || now - entry.statusAt >= STATUS_FRESH_MILLIS || status.minecraftVersion().isEmpty()) {
            String version = minecraftVersion(answer.getVersion().getName());
            if (!version.isEmpty() && !version.equals(entry.minecraftVersion)) {
                entry.minecraftVersion = version;
                save(() -> plugin.database().setBackendPlugin(entry.name, null, null, version, null));
            }
        }
    }

    /**
     * DiscordLinkedBackend's status from its answer to {@link #PROBE_HOST}: a JSON object in the MOTD with
     * {@code "discordlinked": 1} and the plugin's name, then the same values as STATUS. Fields it doesn't have are
     * unknown, ones this proxy doesn't know are ignored. Null if the MOTD isn't that.
     */
    static Status reported(ServerPing answer) {
        try {
            Component description = answer.getDescriptionComponent();
            if (description == null) {
                return null;
            }
            String text = PlainTextComponentSerializer.plainText().serialize(description);
            if (text.length() > MAX_ANSWER_CHARS || !text.strip().startsWith("{")) {
                return null;
            }
            Map<String, Object> json = DataObject.fromJson(text.strip()).toMap();
            if (!(json.get("discordlinked") instanceof Number marker) || marker.doubleValue() != 1
                    || !(json.get("plugin") instanceof String name) || name.isBlank()) {
                return null;
            }
            return new Status(clip(name.strip()), clip(text(json, "version")), clip(text(json, "minecraft")),
                    Boolean.TRUE.equals(json.get("folia")), count(json, "online"), count(json, "max"),
                    measure(json, "tps"), measure(json, "mspt"), json.get("spy") instanceof Boolean spy ? spy : null);
        } catch (RuntimeException e) {
            return null; // not JSON (ParsingException) or something else odd: just a MOTD
        }
    }

    private static String text(Map<String, Object> json, String key) {
        return json.get(key) instanceof String value ? value.strip() : "";
    }

    private static int count(Map<String, Object> json, String key) {
        return json.get(key) instanceof Number value && value.doubleValue() >= 0
                ? (int) Math.min(Integer.MAX_VALUE, value.longValue()) : -1;
    }

    private static double measure(Map<String, Object> json, String key) {
        return json.get(key) instanceof Number value && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0
                ? value.doubleValue() : -1;
    }

    /** Plugin names and versions are short; a broken one can't fill the database (like STATUS, see FadBridge). */
    private static String clip(String text) {
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    /** Whether a version like "0.22.1" or "0.22.1-SNAPSHOT" is at least {@code minimum}. Unreadable ones aren't. */
    static boolean atLeast(String version, int[] minimum) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION_PARTS.matcher(version.strip());
        if (!matcher.find()) {
            return false;
        }
        for (int i = 0; i < minimum.length; i++) {
            String part = matcher.group(i + 1);
            int value;
            try {
                value = part == null ? 0 : Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (value != minimum[i]) {
                return value > minimum[i];
            }
        }
        return true;
    }

    /** "Paper 1.21.4" -> "1.21.4"; something else as it is. */
    static String minecraftVersion(String pingVersion) {
        if (pingVersion == null) {
            return "";
        }
        Matcher matcher = MINECRAFT_VERSION.matcher(pingVersion);
        String version = matcher.find() ? matcher.group() : pingVersion.strip();
        return version.length() > 40 ? version.substring(0, 40) : version;
    }

    // ---- Settings (Settings -> Backend servers) ----

    /**
     * Switches earning credits and/or microcredits on or off on one server ({@code null} leaves it). Takes effect
     * straight away and is written to the audit log. Returns what changed, e.g. "earning credits on -> off".
     */
    public synchronized List<String> setEarning(String server, Boolean credits, Boolean micro, String actor)
            throws SQLException {
        Entry entry = known(server);
        boolean newCredits = credits == null ? entry.credits : credits;
        boolean newMicro = micro == null ? entry.micro : micro;
        List<String> changes = new ArrayList<>();
        if (newCredits != entry.credits) {
            changes.add("earning credits " + onOff(entry.credits) + " -> " + onOff(newCredits));
        }
        if (newMicro != entry.micro) {
            changes.add("earning microcredits " + onOff(entry.micro) + " -> " + onOff(newMicro));
        }
        if (changes.isEmpty()) {
            return changes;
        }
        plugin.database().setBackendEarning(entry.name, newCredits, newMicro);
        boolean pricesChange = newMicro != entry.micro;
        entry.credits = newCredits;
        entry.micro = newMicro;
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "server " + entry.name + ": "
                + String.join(", ", changes), actor, entry.name);
        if (pricesChange) {
            // The server's /sell, /sellhand and /worth follow at once.
            plugin.community().market().pricesChanged();
        }
        return changes;
    }

    /**
     * Turns maintenance mode on or off on one server and/or changes the message for players turned away
     * ({@code null} leaves each; a blank message means the default one). Written to the audit log. Turning it on
     * moves everyone on the server who may not stay to another server (see {@link Maintenance#moveOff}). Returns what
     * changed, e.g. "maintenance off -> on".
     */
    public synchronized List<String> setMaintenance(String server, Boolean on, String message, String actor)
            throws SQLException {
        Entry entry = known(server);
        boolean newOn = on == null ? entry.maintenance : on;
        String newMessage = message == null ? entry.maintenanceMessage : maintenanceText(message);
        List<String> changes = new ArrayList<>();
        if (newOn != entry.maintenance) {
            changes.add("maintenance " + onOff(entry.maintenance) + " -> " + onOff(newOn));
        }
        if (!newMessage.equals(entry.maintenanceMessage)) {
            changes.add("maintenance message " + quoted(entry.maintenanceMessage) + " -> " + quoted(newMessage));
        }
        if (changes.isEmpty()) {
            return changes;
        }
        plugin.database().setBackendMaintenance(entry.name, newOn, newMessage.isEmpty() ? null : newMessage);
        boolean turnedOn = newOn && !entry.maintenance;
        entry.maintenance = newOn;
        entry.maintenanceMessage = newMessage;
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "server " + entry.name + ": "
                + String.join(", ", changes), actor, entry.name);
        if (turnedOn) {
            plugin.maintenance().moveOff(entry.name);
        }
        return changes;
    }

    /** A maintenance message as typed: one line of plain text, at most {@link #MAX_MAINTENANCE_MESSAGE} characters. */
    private static String maintenanceText(String message) {
        String text = message.strip();
        if (text.length() > MAX_MAINTENANCE_MESSAGE) {
            throw new IllegalArgumentException("The maintenance message can be at most " + MAX_MAINTENANCE_MESSAGE
                    + " characters.");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The maintenance message must be one line.");
        }
        return text;
    }

    private static String quoted(String message) {
        return message.isEmpty() ? "(default)" : "\"" + message + "\"";
    }

    /** Sets the server the website's server pickers start on; "" = all servers. Returns its name as spelt. */
    public synchronized String setDefaultServer(String server, String actor) throws IOException {
        String name = server.isBlank() ? "" : known(server).name;
        if (!name.isEmpty() && !registered().contains(key(name))) {
            throw new IllegalArgumentException(name + " isn't in Velocity's config, so nobody can start on it.");
        }
        String old = plugin.config().defaultServer();
        if (name.equals(old)) {
            return name;
        }
        new SettingsEditor(plugin).setDefaultServer(name);
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "default-server: "
                + (old.isEmpty() ? "(all servers)" : old) + " -> " + (name.isEmpty() ? "(all servers)" : name), actor);
        return name;
    }

    /** Takes a server that's no longer in Velocity's config off the list, with its switches. */
    public synchronized void forget(String server, String actor) throws SQLException, IOException {
        Entry entry = known(server);
        if (registered().contains(key(entry.name))) {
            throw new IllegalArgumentException(entry.name + " is in Velocity's config, so it stays on the list.");
        }
        plugin.database().deleteBackendServer(entry.name);
        entries.remove(key(entry.name));
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "server " + entry.name
                + ": taken off the list", actor, entry.name);
        if (entry.name.equalsIgnoreCase(plugin.config().defaultServer())) {
            setDefaultServer("", actor);
        }
    }

    // ---- Helpers ----

    private Server view(Entry entry, boolean registered, int playersHere, long now) {
        // Only servers in Velocity's config are pinged, so a removed one's last answer would be kept for ever.
        Ping ping = registered ? entry.ping : null;
        Status status = entry.status;
        boolean fresh = status != null && now - entry.statusAt < STATUS_FRESH_MILLIS;
        boolean up = ping != null && ping.reachable();
        Integer max = up ? Integer.valueOf(ping.max()) : fresh ? Integer.valueOf(status.max()) : null;
        boolean current = status != null && entry.statusAt >= entry.helloAt;
        return new Server(entry.name, registered, ping == null ? null : ping.reachable(),
                ping == null ? null : Instant.ofEpochMilli(ping.at()), playersHere,
                max == null || max < 0 ? null : max, entry.minecraftVersion, entry.pluginName, entry.pluginVersion,
                current ? status.folia() : null, current ? status.spy() : null,
                fresh && status.tps() >= 0 ? status.tps() : null, fresh && status.mspt() >= 0 ? status.mspt() : null,
                entry.lastSeen == 0 ? null : Instant.ofEpochMilli(entry.lastSeen), registered ? entry.answered : null,
                entry.credits, entry.micro, entry.maintenance, entry.maintenanceMessage);
    }

    /** Keys of the servers in Velocity's config right now; each has an entry (new ones are remembered). */
    private Set<String> registered() {
        Set<String> keys = new HashSet<>();
        for (RegisteredServer server : plugin.proxy().getAllServers()) {
            Entry entry = entry(server.getServerInfo().getName());
            keys.add(key(entry.name));
        }
        return keys;
    }

    /** The server's entry, made (and stored, with both switches on) if it's new. Velocity's spelling wins. */
    private Entry entry(String server) {
        Entry entry = entries.computeIfAbsent(key(server), k -> {
            save(() -> plugin.database().addBackendServer(server));
            return new Entry(server);
        });
        entry.name = server;
        return entry;
    }

    private Entry known(String server) {
        registered();
        Entry entry = server == null ? null : entries.get(key(server.trim()));
        if (entry == null) {
            throw new IllegalArgumentException("There's no server called " + server + ".");
        }
        return entry;
    }

    private void save(SqlWork work) {
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                work.run();
            } catch (SQLException e) {
                plugin.logger().warn("Could not save the backend servers", e);
            }
        }).schedule();
    }

    private static String key(String server) {
        return server.toLowerCase(Locale.ROOT);
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }
}
