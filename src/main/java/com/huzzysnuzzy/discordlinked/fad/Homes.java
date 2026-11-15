package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.Home;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerGroup;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Homes: /sethome, /home, /homes and /delhome on the backend servers, decided here so they're the same on every
 * server. Homes are stored on the proxy with the server they're on, so /home works across servers (the player is
 * moved there first). Players in the same group can use each other's homes ("Name:home"); only the owner moves or
 * removes them.
 *
 * <p>How /home goes: the server asks (HOME_GO); this answers with HOME_TP and the spot. The server makes the
 * player wait home-warmup-seconds (moving or getting hurt stops it), then teleports them and says HOME_DONE. When
 * the home is on another server, the first HOME_TP only has them wait; at its HOME_DONE "ready" they're moved there
 * and that server gets a HOME_TP without a wait.
 */
public final class Homes {

    /** What /sethome and /home <name> take. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    static final String DEFAULT_NAME = "home";
    /** How long a /home may wait for the server's HOME_DONE (on top of the wait itself). */
    private static final long PENDING_MILLIS = 30_000;

    /** A /home on its way: the player, the home, and when it started. */
    private record Pending(UUID player, Home home, long startedAt) {
    }

    private final DiscordLinked plugin;
    private final Community community;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    Homes(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    private PluginConfig config() {
        return plugin.config();
    }

    /** How many homes a player may have: everyone's number plus the extra ones they have, up to the most. */
    public int limit(UUID uuid) throws SQLException {
        PluginConfig c = config();
        return Math.min(c.homesMax(), c.homesDefault() + plugin.database().homeSlots(uuid));
    }

    /** Whether homes work anywhere, so there's something to buy extra homes for. */
    public boolean open() {
        return config().fadEnabled() && plugin.servers().homesAnywhere();
    }

    // ---- From the backend servers ----

    /** /sethome [name] at this spot. */
    void set(Player player, String server, String rawName, String world, double x, double y, double z, float yaw,
             float pitch) {
        if (!usable(player, server)) {
            return;
        }
        String name = rawName.isBlank() ? DEFAULT_NAME : rawName.trim();
        if (!NAME.matcher(name).matches()) {
            say(player, "minecraft.home.bad-name");
            return;
        }
        UUID uuid = player.getUniqueId();
        try {
            int limit = limit(uuid);
            Database.HomeSet result = plugin.database().setHome(uuid, player.getUsername(), name, server, world, x, y, z,
                    yaw, pitch, limit);
            int count = plugin.database().homeCount(uuid);
            switch (result) {
                case FULL -> full(player, count, limit);
                case NEW, MOVED -> {
                    say(player, result == Database.HomeSet.NEW ? "minecraft.home.set" : "minecraft.home.moved",
                            "home", name, "count", String.valueOf(count), "limit", String.valueOf(limit));
                    plugin.audit().log(uuid, player.getUsername(), community.linkedDiscordId(uuid), EventTypes.HOME,
                            (result == Database.HomeSet.NEW ? "set home " : "moved home ") + name + " on " + server
                                    + " (" + world + " " + coords(x, y, z) + ")", null, server);
                    namesChanged(uuid);
                }
            }
        } catch (SQLException e) {
            plugin.logger().error("Could not save a home for {}", player.getUsername(), e);
            say(player, "minecraft.command.database-error");
        }
    }

    /** /home [name]: their own home, or "Name:home" of someone in their group. */
    void go(Player player, String server, String query) {
        if (!usable(player, server)) {
            return;
        }
        Home home;
        try {
            home = find(player, query.trim());
        } catch (SQLException e) {
            plugin.logger().error("Could not look up homes for {}", player.getUsername(), e);
            say(player, "minecraft.command.database-error");
            return;
        }
        if (home == null) {
            return; // find() said why
        }
        if (!plugin.servers().homesEnabled(home.server())) {
            say(player, "minecraft.home.off-there", "home", label(player, home), "server", home.server());
            return;
        }
        Optional<RegisteredServer> target = plugin.proxy().getServer(home.server());
        if (target.isEmpty()) {
            say(player, "minecraft.home.gone-server", "home", label(player, home), "server", home.server());
            return;
        }
        boolean here = home.server().equalsIgnoreCase(server);
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(player.getUniqueId(), home, System.currentTimeMillis()));
        sweep();
        player.getCurrentServer().ifPresent(connection -> teleport(connection, id, player, home, here,
                config().homeWarmupSeconds()));
    }

    /** /delhome <name>: one of their own. */
    void delete(Player player, String server, String rawName) {
        if (!usable(player, server)) {
            return;
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            say(player, "minecraft.home.delete-usage");
            return;
        }
        UUID uuid = player.getUniqueId();
        try {
            if (!plugin.database().deleteHome(uuid, name)) {
                say(player, "minecraft.home.not-found", "home", name);
                return;
            }
        } catch (SQLException e) {
            plugin.logger().error("Could not remove a home of {}", player.getUsername(), e);
            say(player, "minecraft.command.database-error");
            return;
        }
        say(player, "minecraft.home.deleted", "home", name);
        plugin.audit().log(uuid, player.getUsername(), community.linkedDiscordId(uuid), EventTypes.HOME,
                "removed home " + name, null, server);
        namesChanged(uuid);
    }

    /** /homes: theirs (with how many they may have) and their group's, each one clickable. */
    void list(Player player, String server) {
        if (!usable(player, server)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        try {
            List<Home> mine = plugin.database().homes(List.of(uuid));
            List<Home> group = groupHomes(uuid);
            int limit = limit(uuid);
            if (mine.isEmpty() && group.isEmpty()) {
                say(player, "minecraft.home.none", "limit", String.valueOf(limit));
                return;
            }
            say(player, "minecraft.home.list-header", "count", String.valueOf(mine.size()), "limit", String.valueOf(limit));
            for (Home home : mine) {
                player.sendMessage(line(player, home));
            }
            if (!group.isEmpty()) {
                say(player, "minecraft.home.list-group");
                for (Home home : group) {
                    player.sendMessage(line(player, home));
                }
            }
        } catch (SQLException e) {
            plugin.logger().error("Could not list homes for {}", player.getUsername(), e);
            say(player, "minecraft.command.database-error");
        }
    }

    /**
     * The server's answer to a HOME_TP: "ok" (there), "ready" (waited; now move them to the home's server),
     * "cancelled" (they moved or were hurt; the server told them) or a reason it failed.
     */
    void done(Player player, String id, String outcome) {
        Pending request = pending.remove(id);
        if (request == null || !request.player().equals(player.getUniqueId())) {
            return;
        }
        Home home = request.home();
        switch (outcome) {
            case "ok" -> plugin.audit().log(player.getUniqueId(), player.getUsername(),
                    community.linkedDiscordId(player.getUniqueId()), EventTypes.HOME, "went to "
                            + (home.owner().equals(player.getUniqueId()) ? "home " + home.name()
                            : home.ownerName() + "'s home " + home.name()), null, home.server());
            case "ready" -> moveThenTeleport(player, home);
            case "no-world" -> say(player, "minecraft.home.no-world", "home", label(player, home), "world", home.world());
            case "cancelled" -> { }
            default -> say(player, "minecraft.home.failed", "home", label(player, home));
        }
    }

    // ---- Names for tab completion ----

    /** A player got on a server: tell it their home names (for tab completion), once they've finished joining. */
    void joined(Player player, String server) {
        if (!plugin.servers().homesEnabled(server)) {
            return;
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> player.getCurrentServer()
                .filter(c -> c.getServerInfo().getName().equals(server))
                .ifPresent(c -> sendNames(c, player))).delay(2, TimeUnit.SECONDS).schedule();
    }

    /** Someone's homes changed: everyone in their group who's online gets the new names. */
    private void namesChanged(UUID owner) {
        List<UUID> people = new ArrayList<>();
        people.add(owner);
        try {
            plugin.database().groupOf(owner).ifPresent(g -> g.members().forEach(m -> {
                if (!people.contains(m.uuid())) {
                    people.add(m.uuid());
                }
            }));
        } catch (SQLException e) {
            plugin.logger().warn("Could not look up the group of {}", owner, e);
        }
        for (UUID uuid : people) {
            plugin.proxy().getPlayer(uuid).ifPresent(p -> p.getCurrentServer()
                    .filter(c -> plugin.servers().homesEnabled(c.getServerInfo().getName()))
                    .ifPresent(c -> sendNames(c, p)));
        }
    }

    private void sendNames(ServerConnection connection, Player player) {
        List<String> names = new ArrayList<>();
        try {
            plugin.database().homes(List.of(player.getUniqueId())).forEach(h -> names.add(h.name()));
            groupHomes(player.getUniqueId()).forEach(h -> names.add(h.ownerName() + ":" + h.name()));
        } catch (SQLException e) {
            plugin.logger().warn("Could not look up home names for {}", player.getUsername(), e);
            return;
        }
        community.bridge().homeNames(connection, player.getUniqueId(), names);
    }

    // ---- Helpers ----

    /** Homes work here, for this player; says why not otherwise. */
    private boolean usable(Player player, String server) {
        if (!config().fadEnabled() || !plugin.servers().homesEnabled(server)) {
            say(player, "minecraft.home.off");
            return false;
        }
        return true;
    }

    /**
     * The home a /home means. Empty: their only home, else the one called "home", else they're asked which.
     * "Name:home": a group member's. A plain name: their own, else a group member's if exactly one has it.
     */
    private Home find(Player player, String query) throws SQLException {
        UUID uuid = player.getUniqueId();
        List<Home> mine = plugin.database().homes(List.of(uuid));
        if (query.isEmpty()) {
            if (mine.size() == 1) {
                return mine.get(0);
            }
            Optional<Home> home = mine.stream().filter(h -> h.name().equalsIgnoreCase(DEFAULT_NAME)).findFirst();
            if (home.isPresent()) {
                return home.get();
            }
            if (mine.isEmpty() && groupHomes(uuid).isEmpty()) {
                say(player, "minecraft.home.none", "limit", String.valueOf(limit(uuid)));
            } else {
                say(player, "minecraft.home.pick");
                list(player, player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(""));
            }
            return null;
        }
        int colon = query.indexOf(':');
        if (colon >= 0) {
            String owner = query.substring(0, colon);
            String name = query.substring(colon + 1);
            if (owner.equalsIgnoreCase(player.getUsername())) {
                return own(player, mine, name);
            }
            Optional<PlayerGroup> group = plugin.database().groupOf(uuid);
            Optional<PlayerGroup.Member> member = group.flatMap(g -> g.members().stream()
                    .filter(m -> m.name() != null && m.name().equalsIgnoreCase(owner)).findFirst());
            if (member.isEmpty()) {
                say(player, "minecraft.home.not-in-group", "player", owner);
                return null;
            }
            Optional<Home> home = plugin.database().homes(List.of(member.get().uuid())).stream()
                    .filter(h -> h.name().equalsIgnoreCase(name)).findFirst();
            if (home.isEmpty()) {
                say(player, "minecraft.home.not-found", "home", member.get().name() + ":" + name);
                return null;
            }
            return home.get();
        }
        Optional<Home> own = mine.stream().filter(h -> h.name().equalsIgnoreCase(query)).findFirst();
        if (own.isPresent()) {
            return own.get();
        }
        List<Home> shared = groupHomes(uuid).stream().filter(h -> h.name().equalsIgnoreCase(query)).toList();
        if (shared.size() == 1) {
            return shared.get(0);
        }
        say(player, shared.isEmpty() ? "minecraft.home.not-found" : "minecraft.home.ambiguous", "home", query);
        return null;
    }

    private Home own(Player player, List<Home> mine, String name) {
        Optional<Home> home = mine.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst();
        if (home.isEmpty()) {
            say(player, "minecraft.home.not-found", "home", name);
            return null;
        }
        return home.get();
    }

    /** The homes of everyone else in the player's group. */
    private List<Home> groupHomes(UUID uuid) throws SQLException {
        Optional<PlayerGroup> group = plugin.database().groupOf(uuid);
        if (group.isEmpty()) {
            return List.of();
        }
        List<UUID> others = group.get().members().stream().map(PlayerGroup.Member::uuid).filter(u -> !u.equals(uuid)).toList();
        return plugin.database().homes(others);
    }

    /** "base" for their own, "Alex:base" for someone else's. */
    private static String label(Player player, Home home) {
        return home.owner().equals(player.getUniqueId()) ? home.name() : home.ownerName() + ":" + home.name();
    }

    private static String coords(double x, double y, double z) {
        return Math.round(Math.floor(x)) + ", " + Math.round(Math.floor(y)) + ", " + Math.round(Math.floor(z));
    }

    private Component line(Player player, Home home) {
        String label = label(player, home);
        Component link = plugin.messages().minecraft("minecraft.home.list-link", "home", label)
                .clickEvent(ClickEvent.runCommand("/home " + label))
                .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.home.list-hover", "home", label)));
        return plugin.messages().minecraft("minecraft.home.list-line", Placeholder.component("link", link),
                "server", home.server(), "world", home.world(), "coords", coords(home.x(), home.y(), home.z()));
    }

    /** At their limit: how to make room, and where to buy more if they can. */
    private void full(Player player, int count, int limit) {
        boolean canBuy = limit < config().homesMax() && plugin.web() != null
                && community.linkedDiscordId(player.getUniqueId()) != null;
        if (!canBuy) {
            say(player, "minecraft.home.full", "count", String.valueOf(count), "limit", String.valueOf(limit));
            return;
        }
        Component link = plugin.messages().minecraft("minecraft.home.buy-link")
                .clickEvent(ClickEvent.runCommand("/dla shop"))
                .hoverEvent(HoverEvent.showText(plugin.messages().minecraft("minecraft.home.buy-hover",
                        "price", config().credits(config().homePrice()))));
        player.sendMessage(plugin.messages().minecraft("minecraft.home.full-buy", Placeholder.component("link", link),
                "count", String.valueOf(count), "limit", String.valueOf(limit)));
    }

    /** Sends HOME_TP with the texts the server shows, so they're all in messages.properties. */
    private void teleport(ServerConnection connection, String id, Player player, Home home, boolean here, int warmup) {
        String label = label(player, home);
        GsonComponentSerializer json = GsonComponentSerializer.gson();
        TagResolver tags = TagResolver.resolver(Placeholder.unparsed("home", label),
                Placeholder.unparsed("seconds", String.valueOf(warmup)), Placeholder.unparsed("server", home.server()));
        community.bridge().homeTeleport(connection, id, warmup, here, home,
                json.serialize(plugin.messages().minecraft("minecraft.home.warmup", tags)),
                json.serialize(plugin.messages().minecraft("minecraft.home.arrived", tags)),
                json.serialize(plugin.messages().minecraft("minecraft.home.cancel-moved", tags)),
                json.serialize(plugin.messages().minecraft("minecraft.home.cancel-hurt", tags)));
    }

    /** After the wait on their own server: move them to the home's server, then teleport them there. */
    private void moveThenTeleport(Player player, Home home) {
        Optional<RegisteredServer> target = plugin.proxy().getServer(home.server());
        if (target.isEmpty() || !plugin.servers().homesEnabled(home.server())) {
            say(player, "minecraft.home.off-there", "home", label(player, home), "server", home.server());
            return;
        }
        say(player, "minecraft.home.moving", "home", label(player, home), "server", home.server());
        player.createConnectionRequest(target.get()).connect().orTimeout(15, TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null || result == null || !result.isSuccessful()) {
                say(player, "minecraft.home.move-failed", "server", home.server());
                return;
            }
            // Give the server a moment to finish the join before it gets the teleport.
            plugin.proxy().getScheduler().buildTask(plugin, () -> player.getCurrentServer()
                    .filter(c -> c.getServerInfo().getName().equalsIgnoreCase(home.server()))
                    .ifPresentOrElse(c -> {
                        String id = UUID.randomUUID().toString();
                        pending.put(id, new Pending(player.getUniqueId(), home, System.currentTimeMillis()));
                        teleport(c, id, player, home, true, 0);
                    }, () -> say(player, "minecraft.home.move-failed", "server", home.server())))
                    .delay(1500, TimeUnit.MILLISECONDS).schedule();
        });
    }

    /** Forgets /home requests a server never answered (it restarted, or the player left). */
    private void sweep() {
        long oldest = System.currentTimeMillis() - PENDING_MILLIS - config().homeWarmupSeconds() * 1000L;
        pending.values().removeIf(p -> p.startedAt() < oldest);
    }

    private void say(Player player, String key, String... placeholders) {
        if (!plugin.messages().isEmpty(key)) {
            player.sendMessage(plugin.messages().minecraft(key, placeholders));
        }
    }

    // ---- The website ----

    /** Buys one extra home with credits (microcredits pay the rest when the player has that on). */
    public Cosmetics.Result buySlot(LinkedAccount buyer, String actor) throws SQLException {
        PluginConfig c = config();
        if (!open()) {
            return Cosmetics.Result.fail("Homes aren't on on any server.");
        }
        int maxSlots = Math.max(0, c.homesMax() - c.homesDefault());
        if (limit(buyer.uuid()) >= c.homesMax()) {
            return Cosmetics.Result.fail("You already have the most homes anyone can have (" + c.homesMax() + ").");
        }
        Market market = community.market();
        String detail = "Bought an extra home";
        Database.Charge charge = plugin.database().buyHomeSlot(buyer.uuid(), buyer.minecraftName(), c.homePrice(),
                market.shopRate(buyer.uuid()), maxSlots, detail, actor);
        if (charge.balance() == -2) {
            return Cosmetics.Result.fail("You already have the most homes anyone can have (" + c.homesMax() + ").");
        }
        if (!charge.ok()) {
            return Cosmetics.Result.fail(market.shortOf(buyer.uuid(), "an extra home", c.homePrice()));
        }
        int limit = limit(buyer.uuid());
        String exchanged = market.exchangedNote(charge);
        plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.HOME, "bought an extra home for "
                + c.credits(c.homePrice()) + exchanged + ", now " + limit + " homes", actor);
        return new Cosmetics.Result(true, "You can have " + limit + " homes now." + (exchanged.isEmpty() ? "" : " " + exchanged.trim()),
                charge.balance());
    }

    /** Staff give (or take) extra homes for free. Returns how many homes the player may have now. */
    public int giveSlots(UUID uuid, String name, Long discordId, int delta, String actor) throws SQLException {
        int slots = plugin.database().addHomeSlots(uuid, name, delta);
        int limit = limit(uuid);
        plugin.audit().log(uuid, name, discordId, EventTypes.HOME, (delta > 0 ? "given " : "taken ") + Math.abs(delta)
                + " extra home" + (Math.abs(delta) == 1 ? "" : "s") + ", now " + limit + " homes (" + slots + " extra)", actor);
        return limit;
    }

    /** Removes a home from the website (its owner, or staff). */
    public boolean remove(UUID owner, String ownerName, Long discordId, String name, String actor) throws SQLException {
        if (!plugin.database().deleteHome(owner, name)) {
            return false;
        }
        plugin.audit().log(owner, ownerName, discordId, EventTypes.HOME, "removed home " + name + " (website)", actor);
        namesChanged(owner);
        return true;
    }
}
