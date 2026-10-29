package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Cosmetic;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.OwnedCosmetic;
import com.huzzysnuzzy.discordlinked.storage.PlayerRef;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cosmetics bought once with credits on the website: chat prefixes, particle trails, profile cards (an image behind
 * the player in the website's lists and on their page) and KeepInventory. The settings owner makes and prices them;
 * players switch what they have on and off on their profile, one of each kind at a time.
 *
 * <p>Everything is decided here. Each player's Minecraft server is told what they use (prefix text, trail particle,
 * from when KeepInventory works) when they join it and whenever it changes; DiscordLinkedFAD applies it.
 */
public final class Cosmetics {

    public static final String PREFIX = "prefix";
    public static final String TRAIL = "trail";
    /** Particles around a player while they stand still. */
    public static final String AURA = "aura";
    public static final String CARD = "card";
    public static final String KEEP_INVENTORY = "keepinv";
    public static final List<String> KINDS = List.of(PREFIX, TRAIL, AURA, CARD, KEEP_INVENTORY);

    /**
     * Particles a trail or aura can use: ones that need no extra data, in every version DiscordLinkedFAD runs on.
     * (With a colour picked they're drawn as coloured dust instead.)
     */
    public static final List<String> PARTICLES = List.of("heart", "flame", "small_flame", "soul_fire_flame", "note",
            "end_rod", "cherry_leaves", "pale_oak_leaves", "snowflake", "enchant", "happy_villager", "angry_villager",
            "glow", "totem_of_undying", "portal", "reverse_portal", "witch", "electric_spark", "wax_on", "wax_off",
            "scrape", "bubble_pop", "cloud", "crit", "enchanted_hit", "dripping_honey", "falling_nectar",
            "falling_spore_blossom", "sculk_soul", "soul", "white_ash", "composter", "firework", "nautilus", "dolphin",
            "glow_squid_ink", "squid_ink", "egg_crack", "trial_spawner_detection", "ominous_spawning", "dust_plume");

    private static final Pattern COLOR_CODES = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");
    private static final long MAX_PRICE = 1_000_000_000L;
    private static final long PUSH_DELAY_MS = 1500;

    /**
     * What a player has in use, with the look of their trail and aura. {@code keepInventorySince} is when they
     * switched KeepInventory on, or null.
     */
    public record Equipped(Cosmetic prefix, Cosmetic trail, ParticleStyle trailStyle, Cosmetic aura,
                           ParticleStyle auraStyle, Cosmetic card, Instant keepInventorySince) {
        static final Equipped NONE = new Equipped(null, null, null, null, null, null, null);
    }

    /** What a request did, with a message for the website. {@code balance} is -1 when it's not about credits. */
    public record Result(boolean ok, String message, long balance) {
        static Result fail(String message) {
            return new Result(false, message, -1);
        }
    }

    private final DiscordLinked plugin;
    private final Community community;
    private final Path cardDirectory;
    /** By id, in shop order. Reloaded after every change; it's small. */
    private volatile Map<Long, Cosmetic> catalog = Map.of();
    private volatile Map<UUID, Equipped> equipped = Map.of();

    Cosmetics(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
        this.cardDirectory = plugin.dataDirectory().resolve("cards");
    }

    void start() {
        try {
            reload();
        } catch (SQLException e) {
            plugin.logger().error("Could not load the cosmetics", e);
        }
    }

    private synchronized void reload() throws SQLException {
        Map<Long, Cosmetic> items = new LinkedHashMap<>();
        for (Cosmetic cosmetic : plugin.database().cosmetics()) {
            items.put(cosmetic.id(), cosmetic);
        }
        Map<UUID, Equipped> inUse = new HashMap<>();
        for (OwnedCosmetic owned : plugin.database().equippedCosmetics()) {
            Cosmetic c = items.get(owned.cosmeticId());
            if (c == null) {
                continue;
            }
            Equipped e = inUse.getOrDefault(owned.uuid(), Equipped.NONE);
            inUse.put(owned.uuid(), switch (c.kind()) {
                case PREFIX -> new Equipped(c, e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), e.card(), e.keepInventorySince());
                case TRAIL -> new Equipped(e.prefix(), c, style(c, owned), e.aura(), e.auraStyle(), e.card(), e.keepInventorySince());
                case AURA -> new Equipped(e.prefix(), e.trail(), e.trailStyle(), c, style(c, owned), e.card(), e.keepInventorySince());
                case CARD -> new Equipped(e.prefix(), e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), c, e.keepInventorySince());
                default -> new Equipped(e.prefix(), e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), e.card(), owned.equippedAt());
            });
        }
        catalog = items;
        equipped = inUse;
    }

    /** A trail's or aura's own look (as the settings owner made it). */
    public static ParticleStyle style(Cosmetic c) {
        return ParticleStyle.parse(c.style(), ParticleStyle.defaultFor(c.kind()));
    }

    /** The look a player uses: what they picked, else the item's own. */
    public static ParticleStyle style(Cosmetic c, OwnedCosmetic owned) {
        return ParticleStyle.parse(owned == null ? null : owned.style(), style(c));
    }

    private static boolean particles(String kind) {
        return TRAIL.equals(kind) || AURA.equals(kind);
    }

    private PluginConfig config() {
        return plugin.config();
    }

    /** Whether players can buy cosmetics. What they already have works either way. */
    public boolean open() {
        return config().fadEnabled() && config().cosmeticsEnabled();
    }

    public Collection<Cosmetic> catalog() {
        return catalog.values();
    }

    public Cosmetic get(long id) {
        return catalog.get(id);
    }

    public Equipped equipped(UUID uuid) {
        return equipped.getOrDefault(uuid, Equipped.NONE);
    }

    /** The website address of a profile card's image, or null if it has none yet. */
    public static String cardUrl(Cosmetic card) {
        return card == null || !CARD.equals(card.kind()) || card.value().isEmpty() ? null : "/cards/" + card.value();
    }

    /** The profile card a player shows, as an image address, or null. */
    public String cardUrl(UUID uuid) {
        return cardUrl(equipped(uuid).card());
    }

    /** The prefix a player uses (with &amp; colour codes), or null. */
    public String prefix(UUID uuid) {
        Cosmetic prefix = equipped(uuid).prefix();
        return prefix == null ? null : prefix.value();
    }

    /** When KeepInventory starts working for a player (epoch millis), or 0 if it's off. */
    public long keepInventoryFrom(UUID uuid) {
        Instant since = equipped(uuid).keepInventorySince();
        return since == null ? 0 : since.plus(Duration.ofMinutes(config().keepInventoryDelayMinutes())).toEpochMilli();
    }

    // ---- Players ----

    /** Buys a cosmetic for the buyer's own Minecraft account and puts it to use straight away. */
    public Result buy(LinkedAccount buyer, long id, String actor) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (!open()) {
            return Result.fail("The cosmetics shop is closed.");
        }
        if (cosmetic == null || !cosmetic.forSale() || cosmetic.kind().equals(CARD) && cosmetic.value().isEmpty()) {
            return Result.fail("That isn't for sale.");
        }
        PluginConfig c = config();
        String detail = "Bought " + describe(cosmetic);
        long balance = plugin.database().buyCosmetic(buyer.uuid(), buyer.minecraftName(), cosmetic, detail, actor);
        if (balance == -2) {
            return Result.fail("You already have " + cosmetic.name() + ".");
        }
        if (balance < 0) {
            long has = plugin.database().creditAccount(buyer.uuid()).map(a -> a.credits()).orElse(0L);
            return Result.fail(cosmetic.name() + " costs " + c.credits(cosmetic.price()) + " but you have " + c.credits(has) + ".");
        }
        plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.COSMETIC,
                "bought " + describe(cosmetic) + " for " + c.credits(cosmetic.price()) + ", now " + c.credits(balance), actor);
        plugin.database().setCosmeticEquipped(buyer.uuid(), id, cosmetic.kind(), true);
        reload();
        push(buyer.uuid());
        String using = cosmetic.kind().equals(KEEP_INVENTORY) && c.keepInventoryDelayMinutes() > 0
                ? " It starts working in " + c.keepInventoryDelayMinutes() + " minutes."
                : " It's on now.";
        return new Result(true, "Bought " + cosmetic.name() + "!" + using, balance);
    }

    /** Switches one of the player's own cosmetics on or off. */
    public Result equip(UUID uuid, String name, Long discordId, long id, boolean on, String actor) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null) {
            return Result.fail("That cosmetic doesn't exist any more.");
        }
        if (on && cosmetic.kind().equals(CARD) && cosmetic.value().isEmpty()) {
            return Result.fail("That profile card has no picture yet.");
        }
        boolean wasOn = equipped(uuid).keepInventorySince() != null;
        if (!plugin.database().setCosmeticEquipped(uuid, id, cosmetic.kind(), on)) {
            return Result.fail("You don't have " + cosmetic.name() + ".");
        }
        reload();
        push(uuid);
        if (cosmetic.kind().equals(KEEP_INVENTORY) && wasOn != on) {
            int delay = config().keepInventoryDelayMinutes();
            plugin.audit().log(uuid, name, discordId, EventTypes.COSMETIC, on
                    ? "switched KeepInventory on" + (delay > 0 ? " (works after " + delay + " minutes)" : "")
                    : "switched KeepInventory off", actor);
        }
        return new Result(true, on ? cosmetic.name() + " is on." : cosmetic.name() + " is off.", -1);
    }

    /** A player picks how their trail or aura looks ({@code style} null = back to the item's own). */
    public Result setStyle(UUID uuid, long id, ParticleStyle style) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null || !particles(cosmetic.kind())) {
            return Result.fail("That can't be styled.");
        }
        if (!plugin.database().setOwnedStyle(uuid, id, style == null ? null : style.encode())) {
            return Result.fail("You don't have " + cosmetic.name() + ".");
        }
        reload();
        push(uuid);
        return new Result(true, style == null ? cosmetic.name() + " looks like it did in the shop again."
                : "Saved how " + cosmetic.name() + " looks.", -1);
    }

    /** What a player has, in shop order, for their profile. */
    public List<OwnedCosmetic> owned(UUID uuid) throws SQLException {
        List<OwnedCosmetic> owned = new java.util.ArrayList<>(plugin.database().ownedCosmetics(uuid));
        List<Long> order = List.copyOf(catalog.keySet());
        owned.removeIf(o -> !catalog.containsKey(o.cosmeticId()));
        owned.sort(java.util.Comparator.comparingInt(o -> order.indexOf(o.cosmeticId())));
        return owned;
    }

    // ---- The settings owner ----

    /** Adds ({@code id} 0) or changes a cosmetic. Throws IllegalArgumentException saying what's wrong. */
    public Cosmetic save(long id, String kind, String name, String value, ParticleStyle style, long price,
                         boolean forSale, String actor) throws SQLException {
        Cosmetic old = id == 0 ? null : catalog.get(id);
        if (id != 0 && old == null) {
            throw new IllegalArgumentException("That cosmetic doesn't exist any more.");
        }
        String k = old != null ? old.kind() : kind;
        if (!KINDS.contains(k)) {
            throw new IllegalArgumentException("Unknown kind of cosmetic.");
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 32 || n.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The name must be 1 to 32 characters.");
        }
        if (price < 0 || price > MAX_PRICE) {
            throw new IllegalArgumentException("The price must be between 0 and " + MAX_PRICE + ".");
        }
        String v = switch (k) {
            case PREFIX -> {
                String text = value == null ? "" : value.strip();
                String visible = COLOR_CODES.matcher(text).replaceAll("");
                if (text.length() > 64 || visible.isBlank() || visible.length() > 32
                        || text.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("The prefix must show 1 to 32 characters (64 with colour codes).");
                }
                yield text;
            }
            case TRAIL, AURA -> {
                if (!PARTICLES.contains(value)) {
                    throw new IllegalArgumentException("Pick a particle.");
                }
                yield value;
            }
            case CARD -> old == null ? "" : old.value(); // the picture is uploaded separately
            default -> "";
        };
        if (old == null && k.equals(KEEP_INVENTORY)
                && catalog.values().stream().anyMatch(c -> c.kind().equals(KEEP_INVENTORY))) {
            throw new IllegalArgumentException("There's already a KeepInventory item; change that one.");
        }
        String s = particles(k) && style != null ? style.encode() : old == null ? null : old.style();
        long saved = plugin.database().saveCosmetic(new Cosmetic(id, k, n, v, s, price, forSale, old == null ? 0 : old.sort()));
        reload();
        Cosmetic now = catalog.get(saved);
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, (old == null ? "added cosmetic " : "changed cosmetic ")
                + describe(now) + ": " + config().credits(price) + (forSale ? "" : ", not for sale"), actor);
        if (old != null && (!old.value().equals(v) || !java.util.Objects.equals(old.style(), s))) {
            pushEveryoneUsing(saved);
        }
        return now;
    }

    /** Deletes a cosmetic and gives everyone who paid for it their credits back. Returns how many players had it. */
    public int delete(long id, String actor) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null) {
            return 0;
        }
        List<UUID> using = whoUses(id);
        PluginConfig c = config();
        List<Database.Refund> refunds = plugin.database().deleteCosmetic(id,
                "Refund: " + describe(cosmetic) + " was removed from the shop", actor);
        reload();
        for (Database.Refund refund : refunds) {
            if (refund.amount() > 0) {
                plugin.proxy().getPlayer(refund.uuid()).ifPresent(player -> player.sendMessage(plugin.messages().minecraft(
                        "minecraft.cosmetics.refunded", "item", cosmetic.name(), "amount", c.credits(refund.amount()),
                        "balance", c.credits(refund.balance()))));
            }
        }
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "deleted cosmetic " + describe(cosmetic)
                + (refunds.isEmpty() ? "" : "; " + refunds.size() + " players had it and paid ones were refunded"), actor);
        if (cosmetic.kind().equals(CARD)) {
            deleteCardFile(cosmetic.value());
        }
        using.forEach(this::push);
        return refunds.size();
    }

    /** Sets a profile card's picture from an uploaded image. Throws IllegalArgumentException if it isn't one. */
    public Cosmetic setCardImage(long id, byte[] upload, String actor) throws SQLException, IOException {
        Cosmetic card = catalog.get(id);
        if (card == null || !card.kind().equals(CARD)) {
            throw new IllegalArgumentException("That isn't a profile card.");
        }
        CardImages.Image image = CardImages.process(upload);
        Files.createDirectories(cardDirectory);
        String file = "card-" + id + "-" + image.hash() + "." + image.extension();
        Files.write(cardDirectory.resolve(file), image.bytes());
        plugin.database().saveCosmetic(new Cosmetic(id, card.kind(), card.name(), file, card.style(), card.price(),
                card.forSale(), card.sort()));
        if (!card.value().equals(file)) {
            deleteCardFile(card.value());
        }
        reload();
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "new picture for profile card " + card.name()
                + " (" + image.width() + "×" + image.height() + ")", actor);
        return catalog.get(id);
    }

    /** A card picture on disk, or null if there's no such picture. */
    public Path cardFile(String name) {
        if (name == null || !name.matches("card-\\d+-[0-9a-f]{12}\\.(png|jpg)")) {
            return null;
        }
        Path file = cardDirectory.resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    private void deleteCardFile(String name) {
        Path file = cardFile(name);
        if (file != null) {
            try {
                Files.delete(file);
            } catch (IOException e) {
                plugin.logger().warn("Could not delete the old profile card picture {}: {}", file, e.getMessage());
            }
        }
    }

    /** Gives a player a cosmetic for free (the owner, e.g. for an event). */
    public Result give(PlayerRef player, long id, String actor) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null) {
            return Result.fail("That cosmetic doesn't exist any more.");
        }
        if (!plugin.database().giveCosmetic(player.uuid(), id)) {
            return Result.fail(player.name() + " already has " + cosmetic.name() + ".");
        }
        plugin.audit().log(player.uuid(), player.name(), player.discordId(), EventTypes.COSMETIC,
                "was given " + describe(cosmetic), actor);
        plugin.proxy().getPlayer(player.uuid()).ifPresent(online -> online.sendMessage(plugin.messages().minecraft(
                "minecraft.cosmetics.given", "item", cosmetic.name())));
        return new Result(true, player.name() + " now has " + cosmetic.name() + ".", -1);
    }

    /** Takes a cosmetic away again, without refunding it. */
    public Result revoke(PlayerRef player, long id, String actor) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null || !plugin.database().revokeCosmetic(player.uuid(), id)) {
            return Result.fail(player.name() + " doesn't have that.");
        }
        reload();
        push(player.uuid());
        plugin.audit().log(player.uuid(), player.name(), player.discordId(), EventTypes.COSMETIC,
                "had " + describe(cosmetic) + " taken away", actor);
        return new Result(true, cosmetic.name() + " was taken away from " + player.name() + ".", -1);
    }

    private List<UUID> whoUses(long id) {
        return equipped.entrySet().stream().filter(e -> {
            Equipped q = e.getValue();
            return q.prefix() != null && q.prefix().id() == id || q.trail() != null && q.trail().id() == id
                    || q.aura() != null && q.aura().id() == id || q.card() != null && q.card().id() == id;
        }).map(Map.Entry::getKey).toList();
    }

    /** "prefix Tycoon", "trail Hearts", "aura Heart halo", "profile card Sunset", "KeepInventory". */
    static String describe(Cosmetic c) {
        return switch (c.kind()) {
            case PREFIX -> "prefix " + c.name();
            case TRAIL -> "trail " + c.name();
            case AURA -> "aura " + c.name();
            case CARD -> "profile card " + c.name();
            default -> c.name();
        };
    }

    // ---- Telling the Minecraft servers ----

    /** Sends a player's cosmetics to the server they're on, if they're online. */
    void push(UUID uuid) {
        plugin.proxy().getPlayer(uuid).flatMap(Player::getCurrentServer).ifPresent(connection -> push(connection));
    }

    private void push(ServerConnection connection) {
        UUID uuid = connection.getPlayer().getUniqueId();
        Equipped e = config().fadEnabled() ? equipped(uuid) : Equipped.NONE;
        community.bridge().cosmetics(connection, uuid, e.prefix() == null ? "" : e.prefix().value(),
                e.trail() == null ? "" : e.trail().value(), e.trail() == null ? "" : e.trailStyle().encode(),
                e.aura() == null ? "" : e.aura().value(), e.aura() == null ? "" : e.auraStyle().encode(),
                config().fadEnabled() ? keepInventoryFrom(uuid) : 0);
    }

    private void pushEveryoneUsing(long id) {
        whoUses(id).forEach(this::push);
    }

    /** A player just switched servers: tell the new one once they're on it. */
    void joined(Player player, String server) {
        plugin.proxy().getScheduler().buildTask(plugin, () -> player.getCurrentServer()
                .filter(connection -> connection.getServerInfo().getName().equals(server))
                .ifPresent(this::push)).delay(PUSH_DELAY_MS, TimeUnit.MILLISECONDS).schedule();
    }

    /** A server (re)started DiscordLinkedFAD: tell it about everyone on it. */
    void serverReady(String server) {
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.getCurrentServer().filter(connection -> connection.getServerInfo().getName().equals(server))
                    .ifPresent(this::push);
        }
    }

    /** Settings changed (e.g. the KeepInventory delay, or community features switched off): tell everyone. */
    public void settingsChanged() {
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.getCurrentServer().ifPresent(this::push);
        }
    }
}
