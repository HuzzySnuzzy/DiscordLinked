package com.huzzysnuzzy.discordlinked.players;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * Where players connect from, roughly (region and country), for admins only. Their IP addresses are looked up at
 * ipinfo.io ({@code https://ipinfo.io/<ip>/json}, with {@code geoip-token} as a Bearer token when it's set), one at
 * a time on a thread of its own and at most one a second, never from an event thread. Answers are kept per IP in the
 * database and looked up again after {@link #REFRESH}. Private, loopback, link-local and carrier-grade NAT
 * addresses are never sent ("Local network"), nor reserved ones like documentation ranges. Off with
 * {@code geoip-enabled=false}. The address can be pointed elsewhere with {@code -Ddiscordlinked.geoip.url=...}.
 */
public final class Regions {

    public static final String URL_PROPERTY = "discordlinked.geoip.url";
    private static final String DEFAULT_URL = "https://ipinfo.io";
    public static final Duration REFRESH = Duration.ofDays(30);
    private static final long MIN_GAP_MILLIS = 1000;
    /** A failed lookup is logged, then no more for this long. */
    private static final long FAILURE_LOG_MILLIS = 10 * 60_000;
    /** After a "too many requests", nothing is asked for this long. */
    private static final long RATE_LIMIT_PAUSE_MILLIS = 60_000;
    private static final int MAX_QUEUED = 1000;
    private static final Pattern IP_TEXT = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");

    /** What kind of address it is. Only PUBLIC ones are ever sent to ipinfo.io. */
    public enum Kind { PUBLIC, LOCAL, RESERVED, INVALID }

    /**
     * Where an IP address is. {@code label} is what the website shows: "California, United States", "Local network",
     * "Reserved address" or "Unknown".
     */
    public record Region(String label, String country, String countryName, String region, String city, boolean local) {
    }

    private final DiscordLinked plugin;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(MAX_QUEUED);
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Thread worker;
    private volatile long lastFailureLog;

    public Regions(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Whether lookups are on (geoip-enabled). */
    public boolean enabled() {
        return plugin.config().geoipEnabled();
    }

    /**
     * Looks an IP up in the background if it's public, lookups are on, and it isn't known (or is older than
     * {@link #REFRESH}). Returns at once.
     */
    public void lookUp(String ip) {
        if (ip == null || !enabled() || kind(ip) != Kind.PUBLIC || !queued.add(ip)) {
            return;
        }
        if (!queue.offer(ip)) {
            queued.remove(ip);
            return;
        }
        startWorker();
    }

    /** Lookups were just switched on: everyone online is looked up. */
    public void settingsChanged() {
        if (enabled()) {
            for (Player player : plugin.proxy().getAllPlayers()) {
                lookUp(player.getRemoteAddress().getAddress().getHostAddress());
            }
        }
    }

    /** Whether the IP is waiting to be looked up. */
    public boolean pending(String ip) {
        return ip != null && queued.contains(ip);
    }

    /** Where the IP is, as far as known; empty when lookups are off or it hasn't been looked up. */
    public Optional<Region> region(String ip) {
        if (ip == null || !enabled()) {
            return Optional.empty();
        }
        switch (kind(ip)) {
            case LOCAL -> {
                return Optional.of(new Region("Local network", null, null, null, null, true));
            }
            case RESERVED -> {
                return Optional.of(new Region("Reserved address", null, null, null, null, false));
            }
            case INVALID -> {
                return Optional.empty();
            }
            default -> {
                // looked up below
            }
        }
        try {
            return plugin.database().ipRegion(ip).map(Regions::region);
        } catch (SQLException e) {
            plugin.logger().warn("Could not read the region of an IP", e);
            return Optional.empty();
        }
    }

    public void shutdown() {
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // ---- The lookups ----

    private synchronized void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        Thread thread = new Thread(this::work, "DiscordLinked-Regions");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            String ip;
            try {
                ip = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            long pause = 0;
            try {
                if (enabled() && stale(ip)) {
                    long started = System.currentTimeMillis();
                    pause = fetch(ip) ? 0 : RATE_LIMIT_PAUSE_MILLIS;
                    pause = Math.max(pause, MIN_GAP_MILLIS - (System.currentTimeMillis() - started));
                }
            } catch (RuntimeException e) {
                failed("Region lookup of an IP failed: " + e);
            } finally {
                queued.remove(ip);
            }
            if (pause > 0) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private boolean stale(String ip) {
        try {
            Optional<Database.IpRegion> known = plugin.database().ipRegion(ip);
            return known.isEmpty() || known.get().fetchedAt().isBefore(Instant.now().minus(REFRESH));
        } catch (SQLException e) {
            return false;
        }
    }

    /** Asks ipinfo.io and stores the answer. False only when it said "too many requests". */
    private boolean fetch(String ip) {
        String base = System.getProperty(URL_PROPERTY, DEFAULT_URL).strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/" + ip + "/json"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        String token = plugin.config().geoipToken();
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            failed("Could not reach " + base + " to look up players' regions: " + e.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        if (response.statusCode() == 429) {
            failed(base + " says too many region lookups were made"
                    + (token.isEmpty() ? " (a free token from ipinfo.io in geoip-token raises the limit)" : ""));
            return false;
        }
        if (response.statusCode() != 200) {
            failed("Region lookup at " + base + " answered HTTP " + response.statusCode()
                    + (response.statusCode() == 401 || response.statusCode() == 403 ? " (check geoip-token)" : ""));
            return true;
        }
        DataObject json;
        try {
            json = DataObject.fromJson(response.body());
        } catch (RuntimeException e) {
            failed("Region lookup at " + base + " didn't answer with JSON");
            return true;
        }
        boolean bogon = json.getBoolean("bogon", false);
        String country = json.getString("country", "").strip().toUpperCase(Locale.ROOT);
        try {
            plugin.database().saveIpRegion(ip, bogon || !COUNTRY_CODE.matcher(country).matches() ? null : country,
                    bogon ? null : clip(json.getString("region", "")), bogon ? null : clip(json.getString("city", "")),
                    Instant.now());
        } catch (SQLException e) {
            plugin.logger().warn("Could not save the region of an IP", e);
        }
        return true;
    }

    private void failed(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog >= FAILURE_LOG_MILLIS) {
            lastFailureLog = now;
            plugin.logger().warn("{}. Further failures in the next 10 minutes aren't logged.", message);
        }
    }

    // ---- Helpers ----

    private static Region region(Database.IpRegion stored) {
        String country = stored.country();
        if (country == null) {
            return new Region("Unknown", null, null, null, null, false);
        }
        String countryName = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        if (countryName.isEmpty() || countryName.equals(country)) {
            countryName = country;
        }
        String region = stored.region() == null || stored.region().isBlank() ? null : stored.region();
        String label = region == null || region.equalsIgnoreCase(countryName) ? countryName : region + ", " + countryName;
        return new Region(label, country, countryName, region, stored.city(), false);
    }

    private static String clip(String text) {
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    /** What kind of address {@code ip} is, without any DNS lookup. */
    public static Kind kind(String ip) {
        String text = ip.strip();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.isEmpty() || !IP_TEXT.matcher(text).matches() || (!text.contains(":") && !text.contains("."))) {
            return Kind.INVALID;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(text); // a literal address: parsed, never resolved
        } catch (UnknownHostException e) {
            return Kind.INVALID;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return Kind.LOCAL;
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return Kind.RESERVED;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int x = b[0] & 0xFF;
            int y = b[1] & 0xFF;
            int z = b[2] & 0xFF;
            if (x == 100 && (y & 0xC0) == 64) {
                return Kind.LOCAL; // carrier-grade NAT, 100.64.0.0/10
            }
            if (x == 0 || x >= 240 || (x == 192 && y == 0 && (z == 0 || z == 2)) || (x == 198 && (y == 18 || y == 19))
                    || (x == 198 && y == 51 && z == 100) || (x == 203 && y == 0 && z == 113)
                    || (x == 192 && y == 88 && z == 99)) {
                return Kind.RESERVED; // "this network", reserved, IETF, documentation, benchmarking, 6to4 relay
            }
            return Kind.PUBLIC;
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return Kind.LOCAL; // unique local, fc00::/7
        }
        if ((b[0] & 0xE0) != 0x20 || ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D
                && (b[3] & 0xFF) == 0xB8)) {
            return Kind.RESERVED; // outside global unicast 2000::/3, or documentation 2001:db8::/32
        }
        return Kind.PUBLIC;
    }
}
