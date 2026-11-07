package com.huzzysnuzzy.discordlinked.players;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Each player's Minecraft client: the version it speaks (from its protocol), its brand ("vanilla", "fabric"...) and
 * its average ping. The ping is sampled every {@link #SAMPLE_SECONDS} seconds while a player is on (Velocity's
 * estimate, 0 or less = not known yet, skipped). Samples add up in memory and are written every
 * {@link #FLUSH_MINUTES} minutes, when the player leaves and when the proxy stops, so the database holds an all-time
 * sum and count; {@link #client} adds what isn't written yet.
 */
public final class ClientInfo {

    public static final long SAMPLE_SECONDS = 30;
    public static final long FLUSH_MINUTES = 5;
    private static final int MAX_BRAND_LENGTH = 64;

    /** A player's client as the website shows it. {@code averagePing} is null before the first sample. */
    public record Client(Integer protocol, String version, String brand, Long averagePing, long pingSamples,
                         Instant lastPingAt, Instant seenAt) {
    }

    /** An online player's client, and the ping samples not written yet (guarded by the object). */
    private static final class Online {
        final Integer protocol;
        final String version;
        volatile String brand;
        final Instant joinedAt = Instant.now();
        long pendingSum;
        long pendingCount;
        long lastSampleAt;

        Online(Integer protocol, String version, String brand) {
            this.protocol = protocol;
            this.version = version;
            this.brand = brand;
        }
    }

    private final DiscordLinked plugin;
    private final Map<UUID, Online> online = new ConcurrentHashMap<>();

    public ClientInfo(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Starts sampling pings and writing them every few minutes. */
    public void start() {
        plugin.proxy().getScheduler().buildTask(plugin, this::sample)
                .delay(SAMPLE_SECONDS, TimeUnit.SECONDS).repeat(SAMPLE_SECONDS, TimeUnit.SECONDS).schedule();
        plugin.proxy().getScheduler().buildTask(plugin, this::flush)
                .delay(FLUSH_MINUTES, TimeUnit.MINUTES).repeat(FLUSH_MINUTES, TimeUnit.MINUTES).schedule();
    }

    // ---- Events ----

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        Online client = track(player);
        plugin.proxy().getScheduler().buildTask(plugin, () -> save(player.getUniqueId(), client, client.brand)).schedule();
    }

    /** The client says what it is a moment after joining (and again if it changes). */
    @Subscribe
    public void onClientBrand(PlayerClientBrandEvent event) {
        Player player = event.getPlayer();
        String brand = brand(event.getBrand());
        if (brand == null || plugin.proxy().getPlayer(player.getUniqueId()).isEmpty()) {
            return;
        }
        Online client = online.computeIfAbsent(player.getUniqueId(), uuid -> newOnline(player));
        if (brand.equals(client.brand)) {
            return;
        }
        client.brand = brand;
        plugin.proxy().getScheduler().buildTask(plugin, () -> save(player.getUniqueId(), client, brand)).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Online client = online.remove(event.getPlayer().getUniqueId());
        if (client != null) {
            UUID uuid = event.getPlayer().getUniqueId();
            plugin.proxy().getScheduler().buildTask(plugin, () -> write(Map.of(uuid, client))).schedule();
        }
    }

    // ---- Pings ----

    /** One ping sample from everyone online. */
    public void sample() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.proxy().getAllPlayers()) {
            long ping = player.getPing();
            if (ping <= 0) {
                continue;
            }
            Online client = online.computeIfAbsent(player.getUniqueId(), uuid -> newOnline(player));
            synchronized (client) {
                client.pendingSum += ping;
                client.pendingCount++;
                client.lastSampleAt = now;
            }
        }
    }

    /** Writes every online player's samples not written yet, in one go. Also called when the proxy stops. */
    public void flush() {
        write(online);
    }

    /** Takes the clients' pending samples and writes them; if that fails, they're put back for the next try. */
    private void write(Map<UUID, Online> clients) {
        Map<UUID, Database.PingSamples> samples = new HashMap<>();
        Map<Online, Database.PingSamples> taken = new HashMap<>();
        for (Map.Entry<UUID, Online> entry : clients.entrySet()) {
            Online client = entry.getValue();
            synchronized (client) {
                if (client.pendingCount > 0) {
                    Database.PingSamples pending = new Database.PingSamples(client.pendingSum, client.pendingCount,
                            Instant.ofEpochMilli(client.lastSampleAt));
                    samples.put(entry.getKey(), pending);
                    taken.put(client, pending);
                    client.pendingSum = 0;
                    client.pendingCount = 0;
                }
            }
        }
        if (samples.isEmpty()) {
            return;
        }
        try {
            plugin.database().addPings(samples);
        } catch (SQLException e) {
            plugin.logger().warn("Could not save players' pings (they're kept for the next try)", e);
            taken.forEach((client, pending) -> {
                synchronized (client) {
                    client.pendingSum += pending.sum();
                    client.pendingCount += pending.count();
                }
            });
        }
    }

    // ---- Looking clients up ----

    /** A player's client: the one they're on now if online (with every sample), else the last one stored. */
    public Optional<Client> client(UUID uuid) throws SQLException {
        Optional<Database.ClientRecord> stored = plugin.database().client(uuid);
        Online now = online.get(uuid);
        long sum = stored.map(Database.ClientRecord::pingSum).orElse(0L);
        long count = stored.map(Database.ClientRecord::pingCount).orElse(0L);
        Instant last = stored.map(Database.ClientRecord::pingAt).orElse(null);
        if (now != null) {
            synchronized (now) {
                sum += now.pendingSum;
                count += now.pendingCount;
                if (now.pendingCount > 0) {
                    last = Instant.ofEpochMilli(now.lastSampleAt);
                }
            }
            return Optional.of(new Client(now.protocol, now.version,
                    now.brand != null ? now.brand : stored.map(Database.ClientRecord::brand).orElse(null),
                    average(sum, count), count, last, now.joinedAt));
        }
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Database.ClientRecord r = stored.get();
        return Optional.of(new Client(r.protocol(), r.version(), r.brand(), average(sum, count), count, last, r.seenAt()));
    }

    /** The client of a player online now, without the stored ping history (for the online list); null if unknown. */
    public Client onlineClient(UUID uuid) {
        Online now = online.get(uuid);
        return now == null ? null : new Client(now.protocol, now.version, now.brand, null, 0, null, now.joinedAt);
    }

    // ---- Helpers ----

    private Online track(Player player) {
        Online client = newOnline(player);
        Online old = online.putIfAbsent(player.getUniqueId(), client);
        return old != null ? old : client;
    }

    private static Online newOnline(Player player) {
        ProtocolVersion protocol = player.getProtocolVersion();
        boolean known = protocol != null && !protocol.isUnknown() && !protocol.isLegacy();
        return new Online(known ? Integer.valueOf(protocol.getProtocol()) : null, known ? versionName(protocol) : null,
                brand(player.getClientBrand()));
    }

    private void save(UUID uuid, Online client, String brand) {
        try {
            plugin.database().setClient(uuid, client.protocol, client.version, brand, Instant.now());
        } catch (SQLException e) {
            plugin.logger().warn("Could not save the Minecraft client of {}", uuid, e);
        }
    }

    /** "1.21.4", or "1.21.2–1.21.3" for a protocol several releases share. */
    public static String versionName(ProtocolVersion protocol) {
        List<String> names = protocol.getVersionsSupportedBy();
        if (names.isEmpty()) {
            return protocol.getVersionIntroducedIn();
        }
        return names.size() == 1 ? names.get(0) : names.get(0) + "–" + names.get(names.size() - 1);
    }

    /** The brand as shown: printable, at most {@link #MAX_BRAND_LENGTH} characters; null if there's nothing left. */
    static String brand(String brand) {
        if (brand == null) {
            return null;
        }
        String text = brand.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (text.length() > MAX_BRAND_LENGTH) {
            text = text.substring(0, MAX_BRAND_LENGTH);
        }
        return text.isEmpty() ? null : text;
    }

    private static Long average(long sum, long count) {
        return count == 0 ? null : Math.round((double) sum / count);
    }
}
