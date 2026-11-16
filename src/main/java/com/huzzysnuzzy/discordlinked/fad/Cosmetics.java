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
 * Cosmetics bought once with credits on the website: chat prefixes, particles (each used for free as the player's
 * trail, aura or both), profile cards (an image behind the player in the website's lists and on their page) and
 * KeepInventory. The settings owner makes and prices them (particles: every Minecraft particle, priced); players switch
 * what they have on and off on their profile, one prefix, trail, aura and card at a time.
 *
 * <p>Everything is decided here. Each player's Minecraft server is told what they use (prefix text, trail particle,
 * from when KeepInventory works) when they join it and whenever it changes; the backend plugin applies it.
 */
public final class Cosmetics {

    public static final String PREFIX = "prefix";
    /**
     * A Minecraft particle, bought once and then used for free as the player's trail (while walking), aura (while
     * standing still) or both, each with its own look.
     */
    public static final String PARTICLE = "particle";
    /** Separate trail and aura items from before 0.28.0, turned into particles at start. Only uses are called this now. */
    public static final String TRAIL = "trail";
    public static final String AURA = "aura";
    public static final String CARD = "card";
    public static final String KEEP_INVENTORY = "keepinv";
    /** Kinds the settings owner can add (particles come from {@link #PARTICLES}, one item each). */
    public static final List<String> KINDS = List.of(PREFIX, CARD, KEEP_INVENTORY);

    /**
     * Every particle a trail or aura can use: all of Minecraft 1.21.4's (the oldest version the backend plugin runs
     * on) that need nothing more than a position, plus dust and potion swirls, which take a colour. Left out: ones
     * that need a block or item, and the ones that would bother other players (the elder guardian's face, explosion
     * and gust emitters). Each is one item in the shop, named after its Minecraft name.
     */
    public static final List<String> PARTICLES = List.of(
            "poof", "explosion", "firework", "bubble", "splash", "fishing", "underwater", "crit", "enchanted_hit",
            "smoke", "large_smoke", "effect", "instant_effect", "entity_effect", "witch", "dripping_water",
            "dripping_lava", "angry_villager", "happy_villager", "mycelium", "note", "portal", "enchant", "flame",
            "lava", "cloud", "dust", "item_snowball", "item_slime", "heart", "rain", "dragon_breath", "end_rod",
            "damage_indicator", "sweep_attack", "totem_of_undying", "spit", "squid_ink", "bubble_pop", "current_down",
            "bubble_column_up", "nautilus", "dolphin", "sneeze", "campfire_cosy_smoke", "campfire_signal_smoke",
            "composter", "flash", "falling_lava", "landing_lava", "falling_water", "dripping_honey", "falling_honey",
            "landing_honey", "falling_nectar", "soul_fire_flame", "ash", "crimson_spore", "warped_spore", "soul",
            "dripping_obsidian_tear", "falling_obsidian_tear", "landing_obsidian_tear", "reverse_portal", "white_ash",
            "falling_spore_blossom", "spore_blossom_air", "small_flame", "snowflake", "dripping_dripstone_lava",
            "falling_dripstone_lava", "dripping_dripstone_water", "falling_dripstone_water", "glow_squid_ink", "glow",
            "wax_on", "wax_off", "electric_spark", "scrape", "sonic_boom", "sculk_soul", "sculk_charge_pop",
            "cherry_leaves", "pale_oak_leaves", "egg_crack", "dust_plume", "white_smoke", "gust", "small_gust",
            "trial_spawner_detection", "trial_spawner_detection_ominous", "vault_connection", "infested", "item_cobweb",
            "ominous_spawning", "raid_omen", "trial_omen");

    /** New particles' price until the settings owner changes it. */
    public static final long DEFAULT_PARTICLE_PRICE = 250;

    /** Particles Minecraft can colour: dust, and potion swirls. */
    public static final java.util.Set<String> COLORABLE = java.util.Set.of("dust", "entity_effect");

    private static final Pattern COLOR_CODES = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");
    /** MiniMessage tags (prefixes can use them as well as &-codes): <red>, </b>, <gradient:#f00:#00f>, <#ff88cc>. */
    private static final Pattern MINI_TAGS = Pattern.compile("</?([a-zA-Z_#][a-zA-Z0-9_#:.-]*)>");
    /** Tags that do something rather than colour or style text: never in a prefix. */
    private static final java.util.Set<String> ACTION_TAGS = java.util.Set.of("click", "hover", "insert", "insertion",
            "newline", "br", "key", "keybind", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or", "score",
            "selector", "sel", "nbt", "data", "font", "sprite", "head", "object");
    private static final long MAX_PRICE = 1_000_000_000L;
    private static final long PUSH_DELAY_MS = 1500;

    /** "soul_fire_flame" → "Soul Fire Flame": the particle's Minecraft name. */
    public static String particleName(String key) {
        StringBuilder name = new StringBuilder();
        for (String word : key.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.equals("of") && !name.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return name.toString();
    }

    /**
     * What a player has in use, with the look of their trail and aura (particle items). {@code keepInventorySince} is
     * when they switched KeepInventory on, or null.
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
            List<String[]> particles = PARTICLES.stream().map(key -> new String[] {key, particleName(key)}).toList();
            int added = plugin.database().addMissingParticles(particles, DEFAULT_PARTICLE_PRICE);
            if (added > 0) {
                plugin.logger().info("Added {} particles to the Cosmetics shop.", added);
            }
            migrateTrailsAndAuras();
            reload();
        } catch (SQLException e) {
            plugin.logger().error("Could not load the cosmetics", e);
        }
    }

    /** Old separate trail and aura items become particles (once; nothing happens when there are none). */
    private void migrateTrailsAndAuras() throws SQLException {
        Map<String, Long> ids = new HashMap<>();
        for (Cosmetic c : plugin.database().cosmetics()) {
            if (c.kind().equals(PARTICLE)) {
                ids.put(c.value(), c.id());
            }
        }
        Database.ParticleMigration done = plugin.database().migrateTrailsAndAuras(ids,
                "Refund: you had a trail and an aura of the same particle, which is now one particle", "DiscordLinked");
        if (done.items() > 0) {
            plugin.logger().info("Trails and auras are particles now: {} old items moved for {} players ({} refunds, {} "
                    + "credits).", done.items(), done.players(), done.refunds(), done.refunded());
            plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "trails and auras became particles: "
                    + done.items() + " items for " + done.players() + " players moved, " + done.refunds()
                    + " double payments refunded (" + config().credits(done.refunded()) + ")", "DiscordLinked");
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
            if (c.kind().equals(PARTICLE)) {
                if (owned.trail()) {
                    e = new Equipped(e.prefix(), c, trailStyle(c, owned), e.aura(), e.auraStyle(), e.card(), e.keepInventorySince());
                }
                if (owned.aura()) {
                    e = new Equipped(e.prefix(), e.trail(), e.trailStyle(), c, auraStyle(c, owned), e.card(), e.keepInventorySince());
                }
                inUse.put(owned.uuid(), e);
                continue;
            }
            if (!owned.equipped()) {
                continue;
            }
            inUse.put(owned.uuid(), switch (c.kind()) {
                case PREFIX -> new Equipped(c, e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), e.card(), e.keepInventorySince());
                case CARD -> new Equipped(e.prefix(), e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), c, e.keepInventorySince());
                case KEEP_INVENTORY -> new Equipped(e.prefix(), e.trail(), e.trailStyle(), e.aura(), e.auraStyle(), e.card(), owned.equippedAt());
                default -> e;
            });
        }
        catalog = items;
        equipped = inUse;
    }

    /** Whether a trail's or aura's particle can be coloured. */
    public static boolean colorable(Cosmetic c) {
        return COLORABLE.contains(c.value());
    }

    /** A particle's default look as a trail. */
    public static ParticleStyle trailDefault(Cosmetic c) {
        return withoutColor(c, ParticleStyle.defaultFor(TRAIL));
    }

    /** A particle's default look as an aura. */
    public static ParticleStyle auraDefault(Cosmetic c) {
        return withoutColor(c, ParticleStyle.defaultFor(AURA));
    }

    /** The look a player uses for a particle as their trail: what they picked, else the default. */
    public static ParticleStyle trailStyle(Cosmetic c, OwnedCosmetic owned) {
        return withoutColor(c, ParticleStyle.parse(owned == null ? null : owned.trailStyle(), trailDefault(c)));
    }

    /** The same as their aura. */
    public static ParticleStyle auraStyle(Cosmetic c, OwnedCosmetic owned) {
        return withoutColor(c, ParticleStyle.parse(owned == null ? null : owned.auraStyle(), auraDefault(c)));
    }

    /** Particles that can't be coloured always look like themselves (whatever was saved before). */
    private static ParticleStyle withoutColor(Cosmetic c, ParticleStyle style) {
        return colorable(c) || style.color().isEmpty() ? style : new ParticleStyle(style.shape(), style.animation(), "");
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

    /** The prefix a player uses (with &amp; colour codes and/or MiniMessage tags), or null. */
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
        Market market = plugin.community().market();
        Database.Charge charge = plugin.database().buyCosmetic(buyer.uuid(), buyer.minecraftName(), cosmetic,
                market.shopRate(buyer.uuid()), detail, actor);
        if (charge.balance() == -2) {
            return Result.fail("You already have " + cosmetic.name() + ".");
        }
        if (!charge.ok()) {
            return Result.fail(market.shortOf(buyer.uuid(), cosmetic.name(), cosmetic.price()));
        }
        long balance = charge.balance();
        String exchanged = market.exchangedNote(charge);
        plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.COSMETIC,
                "bought " + describe(cosmetic) + " for " + c.credits(cosmetic.price()) + exchanged + ", now "
                        + c.credits(balance), actor);
        String using;
        if (cosmetic.kind().equals(PARTICLE)) {
            // Put to use where nothing is: as their trail, else as their aura; otherwise they pick.
            Equipped now = equipped(buyer.uuid());
            if (now.trail() == null) {
                plugin.database().setParticleUse(buyer.uuid(), id, false, true);
                using = " It's your trail now; use it as your aura too if you like.";
            } else if (now.aura() == null) {
                plugin.database().setParticleUse(buyer.uuid(), id, true, true);
                using = " It's your aura now; use it as your trail too if you like.";
            } else {
                using = " Use it as your trail or aura whenever you like.";
            }
        } else {
            plugin.database().setCosmeticEquipped(buyer.uuid(), id, cosmetic.kind(), true);
            using = cosmetic.kind().equals(KEEP_INVENTORY) && c.keepInventoryDelayMinutes() > 0
                    ? " It starts working in " + c.keepInventoryDelayMinutes() + " minutes."
                    : " It's on now.";
        }
        reload();
        push(buyer.uuid());
        return new Result(true, "Bought " + cosmetic.name() + "!" + exchanged + using, balance);
    }

    /**
     * Switches one of the player's own cosmetics on or off; a particle as their trail or aura ({@code as}), which
     * costs nothing.
     */
    public Result equip(UUID uuid, String name, Long discordId, long id, boolean on, String as, String actor)
            throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        if (cosmetic == null) {
            return Result.fail("That cosmetic doesn't exist any more.");
        }
        if (cosmetic.kind().equals(PARTICLE)) {
            boolean aura = AURA.equals(as);
            if (!aura && !TRAIL.equals(as)) {
                return Result.fail("Use it as a trail or as an aura.");
            }
            if (!plugin.database().setParticleUse(uuid, id, aura, on)) {
                return Result.fail("You don't have " + cosmetic.name() + ".");
            }
            reload();
            push(uuid);
            return new Result(true, cosmetic.name() + (on ? " is your " : " is no longer your ") + (aura ? "aura." : "trail."), -1);
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

    /** A player picks how a particle looks as their trail or aura ({@code style} null = back to the default). */
    public Result setStyle(UUID uuid, long id, String as, ParticleStyle style) throws SQLException {
        Cosmetic cosmetic = catalog.get(id);
        boolean aura = AURA.equals(as);
        if (cosmetic == null || !cosmetic.kind().equals(PARTICLE) || !aura && !TRAIL.equals(as)) {
            return Result.fail("That can't be styled.");
        }
        if (style != null && !style.color().isEmpty() && !colorable(cosmetic)) {
            return Result.fail(cosmetic.name() + " can't be coloured.");
        }
        if (!plugin.database().setParticleStyle(uuid, id, aura, style == null ? null : style.encode())) {
            return Result.fail("You don't have " + cosmetic.name() + ".");
        }
        reload();
        push(uuid);
        String use = aura ? " as an aura" : " as a trail";
        return new Result(true, style == null ? cosmetic.name() + use + " looks like it does by default again."
                : "Saved how " + cosmetic.name() + " looks" + use + ".", -1);
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
        if (!KINDS.contains(k) && !(old != null && k.equals(PARTICLE))) {
            throw new IllegalArgumentException(PARTICLE.equals(k) ? "Every Minecraft particle is in the shop already."
                    : "Unknown kind of cosmetic.");
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
                java.util.regex.Matcher tags = MINI_TAGS.matcher(text);
                while (tags.find()) {
                    String tag = tags.group(1).split(":", 2)[0].toLowerCase(java.util.Locale.ROOT);
                    if (ACTION_TAGS.contains(tag)) {
                        throw new IllegalArgumentException("Prefixes can only colour and style text: <" + tag
                                + "> isn't allowed.");
                    }
                }
                String visible = MINI_TAGS.matcher(COLOR_CODES.matcher(text).replaceAll("")).replaceAll("");
                if (text.length() > 256 || visible.isBlank() || visible.length() > 32
                        || text.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("The prefix must show 1 to 32 characters (up to 256 with colour "
                            + "codes and MiniMessage tags).");
                }
                yield text;
            }
            case PARTICLE -> old.value(); // the particle itself never changes
            case CARD -> old == null ? "" : old.value(); // the picture is uploaded separately
            default -> "";
        };
        if (old == null && k.equals(KEEP_INVENTORY)
                && catalog.values().stream().anyMatch(c -> c.kind().equals(KEEP_INVENTORY))) {
            throw new IllegalArgumentException("There's already a KeepInventory item; change that one.");
        }
        String s = old == null ? null : old.style();
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
        if (cosmetic.kind().equals(PARTICLE)) {
            throw new IllegalArgumentException("Particles stay in the shop (they'd come back at the next start); take it "
                    + "off sale instead.");
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
                + (refunds.isEmpty() ? "" : "; " + refunds.size() + (refunds.size() == 1 ? " player" : " players")
                        + " had it and paid ones were refunded"), actor);
        if (cosmetic.kind().equals(CARD)) {
            deleteCardFile(cosmetic.value());
        }
        using.forEach(this::push);
        return refunds.size();
    }

    /** Sets every particle's price (the settings owner). Returns how many particles there are. */
    public int setParticlePrices(long price, String actor) throws SQLException {
        if (price < 0 || price > MAX_PRICE) {
            throw new IllegalArgumentException("The price must be between 0 and " + MAX_PRICE + ".");
        }
        int count = plugin.database().setParticlePrices(price);
        reload();
        plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, "set every particle's price to "
                + config().credits(price), actor);
        return count;
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
            case PARTICLE -> "particle " + c.name();
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

    /** A server (re)started its backend plugin: tell it about everyone on it. */
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
