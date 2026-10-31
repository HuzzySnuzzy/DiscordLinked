package com.huzzysnuzzy.discordlinked.servers;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.SettingsEditor;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every backend server behind the proxy: the ones registered in Velocity, plus any the database remembers (so a
 * server's switches survive it being out of Velocity's config for a while). For each it knows whether it's up
 * (pinged every 30 seconds), what its plugin reported (HELLO, and STATUS from DiscordLinkedBackend) and the settings
 * owner's switches for earning credits and microcredits there. Names are matched ignoring case, like Velocity does.
 */
public final class BackendServers {

    public static final String PLUGIN = "DiscordLinkedBackend";
    /** A server that says HELLO but no STATUS runs this, from before it was merged into DiscordLinkedBackend. */
    public static final String OLD_PLUGIN = "DiscordLinkedFAD";
    private static final long PING_SECONDS = 30;
    private static final long PING_TIMEOUT_SECONDS = 4;
    /** How long the website waits for fresh pings before it shows what it has. */
    private static final long REFRESH_WAIT_MILLIS = 1500;
    /** DiscordLinkedBackend sends STATUS straight after HELLO_OK; a HELLO with no STATUS by then is the old plugin. */
    private static final long STATUS_WAIT_SECONDS = 10;
    /** TPS and MSPT are shown while STATUS keeps coming (every 30 seconds while someone is on the server). */
    private static final long STATUS_FRESH_MILLIS = 90_000;
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

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
     */
    public record Server(String name, boolean registered, Boolean reachable, Instant checkedAt, int players,
                         Integer maxPlayers, String minecraftVersion, String pluginName, String pluginVersion,
                         Boolean folia, Boolean spy, Double tps, Double mspt, Instant lastSeen,
                         boolean creditsEnabled, boolean microEnabled) {

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
        volatile String pluginName;
        volatile String pluginVersion;
        volatile String minecraftVersion;
        volatile long lastSeen;
        /** The last HELLO since the proxy started (0 = none) and the version it gave. */
        volatile long helloAt;
        volatile String helloVersion;
        volatile Status status;
        volatile long statusAt;
        volatile Ping ping;

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
                entry.pluginName = row.pluginName();
                entry.pluginVersion = row.pluginVersion();
                entry.minecraftVersion = row.minecraftVersion();
                entry.lastSeen = row.lastSeen() == null ? 0 : row.lastSeen().toEpochMilli();
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

    /** The server the website's server pickers start on, or "" for all servers (also if it no longer exists). */
    public String defaultServer() {
        String server = plugin.config().defaultServer();
        if (server.isEmpty()) {
            return "";
        }
        String name = name(server);
        return name == null ? "" : name;
    }

    // ---- From the backend plugin (discordlinked:fad) ----

    /**
     * A server's plugin said hello. DiscordLinkedBackend follows up with STATUS right away; if none comes, it's
     * DiscordLinkedFAD from before the merge.
     */
    public void hello(String server, String version) {
        Entry entry = entry(server);
        long now = System.currentTimeMillis();
        entry.helloAt = now;
        entry.helloVersion = version;
        entry.lastSeen = now;
        save(() -> plugin.database().setBackendPlugin(entry.name, null, null, null, Instant.ofEpochMilli(now)));
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (entry.helloAt == now && entry.statusAt < now) {
                entry.pluginName = OLD_PLUGIN;
                entry.pluginVersion = version;
                save(() -> plugin.database().setBackendPlugin(entry.name, OLD_PLUGIN, version, null, null));
            }
        }).delay(STATUS_WAIT_SECONDS, TimeUnit.SECONDS).schedule();
    }

    /** DiscordLinkedBackend's regular STATUS (after HELLO, then every 30 seconds while someone is on the server). */
    public void status(String server, Status status) {
        Entry entry = entry(server);
        long now = System.currentTimeMillis();
        entry.status = status;
        entry.statusAt = now;
        entry.lastSeen = now;
        entry.pluginName = status.plugin();
        entry.pluginVersion = status.version();
        if (!status.minecraftVersion().isEmpty()) {
            entry.minecraftVersion = status.minecraftVersion();
        }
        String minecraft = entry.minecraftVersion;
        save(() -> plugin.database().setBackendPlugin(entry.name, status.plugin(), status.version(), minecraft,
                Instant.ofEpochMilli(now)));
    }

    // ---- Pinging ----

    /** Pings every server in Velocity's config. Completes when all have answered or timed out. */
    public CompletableFuture<Void> refresh() {
        lastRefresh = System.currentTimeMillis();
        List<CompletableFuture<Void>> pings = new ArrayList<>();
        for (RegisteredServer server : plugin.proxy().getAllServers()) {
            Entry entry = entry(server.getServerInfo().getName());
            CompletableFuture<ServerPing> ping;
            try {
                ping = server.ping();
            } catch (RuntimeException e) {
                ping = CompletableFuture.failedFuture(e);
            }
            pings.add(ping.orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS).handle((answer, error) -> {
                pinged(entry, error == null ? answer : null);
                return null;
            }));
        }
        return CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new));
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

    private void pinged(Entry entry, ServerPing answer) {
        long now = System.currentTimeMillis();
        if (answer == null) {
            entry.ping = new Ping(false, 0, now);
            return;
        }
        entry.ping = new Ping(true, answer.getPlayers().map(ServerPing.Players::getMax).orElse(-1), now);
        // Without a recent STATUS, the version the server tells pinging clients ("Paper 1.21.4") is the best there is.
        Status status = entry.status;
        if (status == null || status.minecraftVersion().isEmpty()) {
            String version = minecraftVersion(answer.getVersion().getName());
            if (!version.isEmpty() && !version.equals(entry.minecraftVersion)) {
                entry.minecraftVersion = version;
                save(() -> plugin.database().setBackendPlugin(entry.name, null, null, version, null));
            }
        }
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

    /** Sets the server the website's server pickers start on; "" = all servers. Returns its name as spelt. */
    public synchronized String setDefaultServer(String server, String actor) throws IOException {
        String name = server.isBlank() ? "" : known(server).name;
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
        Ping ping = entry.ping;
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
                entry.lastSeen == 0 ? null : Instant.ofEpochMilli(entry.lastSeen), entry.credits, entry.micro);
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
