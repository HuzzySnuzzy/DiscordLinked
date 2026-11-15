package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.ShopOverride;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The website's Shop: Minecraft items for credits. An order is up to a chest's worth of stacks; it's paid for
 * (plus the chest itself), then the player's server puts it in their inventory as one chest holding the items.
 * If the server can't (inventory full, unknown item, no answer), the credits are given back.
 *
 * <p>Prices are kept in hundredths of a credit: the built-in price (shop-items.json, see tools/shop_prices.py)
 * times shop-price-percent times the item's own percentage. An order costs the sum, rounded up to whole credits.
 */
public final class Shop {

    public static final int CHEST_SLOTS = 27;
    private static final String CHEST = "chest";
    /** The chest every order comes in. */
    static final String CHEST_ID = CHEST;
    /** The server gives up delivering after 8 seconds, so by 12 nothing can still arrive and a refund is safe. */
    private static final long DELIVERY_TIMEOUT_SECONDS = 12;

    public record Item(String id, String tab, int stack, long cents) {
    }

    /**
     * How an order is delivered: in a chest or a shulker box (bought with it, so it's added to the price), or
     * straight into the inventory (what doesn't fit is dropped at the player's feet).
     */
    public enum Container {
        CHEST("chest", "in a chest"), SHULKER("shulker", "in a shulker box"), DIRECT("direct", "into their inventory");

        public final String id;
        final String described;

        Container(String id, String described) {
            this.id = id;
            this.described = described;
        }

        public static Container of(String id) {
            for (Container c : values()) {
                if (c.id.equals(id)) {
                    return c;
                }
            }
            return CHEST;
        }
    }

    /** One chest slot of an order. */
    public record Line(String id, int amount) {
    }

    /** What happened to an order; {@code balance} is -1 when nothing was charged. */
    public record Result(boolean ok, String message, long cost, long balance) {
    }

    private record Delivery(UUID player, CompletableFuture<String> outcome) {
    }

    private final DiscordLinked plugin;
    private final Community community;
    /** shop-items.json: the items of the Minecraft version it was made for. */
    private final Map<String, Item> builtIn;
    /** Items in that version that aren't sold on purpose (no survival way to get them, or they need extra data). */
    private final Set<String> excluded;
    /** The built-in items plus guesses for items only newer Minecraft versions have (see {@link #guess}). */
    private volatile Map<String, Item> catalog;
    /** Which items in the catalog are guesses. */
    private volatile Set<String> guessed = Set.of();
    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();
    private final Set<UUID> checkingOut = ConcurrentHashMap.newKeySet();
    /** Items each server knows (from its backend plugin), so items from newer Minecraft versions aren't offered. */
    private final Map<String, Set<String>> serverItems = new ConcurrentHashMap<>();

    Shop(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
        DataObject file = readCatalogFile();
        DataArray items = file.getArray("items");
        Map<String, Item> catalog = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            DataObject item = items.getObject(i);
            catalog.put(item.getString("id"), new Item(item.getString("id"), item.getString("tab"),
                    item.getInt("stack"), item.getLong("price")));
        }
        this.builtIn = Collections.unmodifiableMap(catalog);
        Set<String> skip = new java.util.HashSet<>();
        DataArray excludedItems = file.optArray("excluded").orElse(DataArray.empty());
        for (int i = 0; i < excludedItems.length(); i++) {
            skip.add(excludedItems.getString(i));
        }
        this.excluded = Set.copyOf(skip);
        this.catalog = builtIn;
    }

    private static DataObject readCatalogFile() {
        try (InputStream in = Shop.class.getResourceAsStream("/shop-items.json")) {
            if (in == null) {
                throw new IllegalStateException("shop-items.json missing from the plugin jar");
            }
            return DataObject.fromJson(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read shop-items.json", e);
        }
    }

    /** Everything the shop can sell: the built-in items, plus guessed ones for newer Minecraft versions. */
    public Map<String, Item> catalog() {
        return catalog;
    }

    /** Whether an item's tab, stack size and price are guessed (it's newer than shop-items.json). */
    public boolean guessed(String id) {
        return guessed.contains(id);
    }

    /** Older Minecraft names of items the built-in list has under their new name (1.21.9 renamed the chain). */
    private static final Map<String, String> RENAMED = Map.of("chain", "iron_chain");

    /** Never guessed, whatever the version: not obtainable in survival, or only useful with data the shop can't set. */
    private static final java.util.regex.Pattern NEVER = java.util.regex.Pattern.compile(
            "_spawn_egg$|_map$|command_block|^test_|structure_|jigsaw|debug|barrier|^light$|knowledge_book|_potion$"
                    + "|^potion$|enchanted_book|tipped_arrow|written_book|^infested_|^suspicious_|spawner|^vault$");

    /**
     * A guess for an item shop-items.json doesn't have (from a newer Minecraft version than it was made for): the
     * catalog items whose names end the same way, the longest such ending first ("maple_log" -> the logs,
     * "red_cushion_slab" -> "_cushion_slab", then "_slab"). Their most common tab and stack size, and their median
     * price. Null when nothing is alike, or the item isn't sold on purpose.
     */
    Item guess(String id) {
        if (excluded.contains(id) || NEVER.matcher(id).find()) {
            return null;
        }
        // Items an older Minecraft calls something else: the same item.
        String newName = RENAMED.get(id);
        if (newName != null && builtIn.containsKey(newName)) {
            Item item = builtIn.get(newName);
            return new Item(id, item.tab(), item.stack(), item.cents());
        }
        String[] parts = id.split("_");
        for (int k = 1; k < parts.length; k++) {
            String ending = "_" + String.join("_", java.util.Arrays.copyOfRange(parts, k, parts.length));
            List<Item> alike = builtIn.values().stream().filter(i -> i.id().endsWith(ending)).toList();
            if (alike.isEmpty()) {
                continue;
            }
            String tab = mostCommon(alike.stream().map(Item::tab).toList());
            int stack = mostCommon(alike.stream().map(Item::stack).toList());
            long[] prices = alike.stream().mapToLong(Item::cents).sorted().toArray();
            return new Item(id, tab, stack, prices[prices.length / 2]);
        }
        return null;
    }

    private static <T> T mostCommon(List<T> values) {
        Map<T, Integer> counts = new LinkedHashMap<>();
        values.forEach(v -> counts.merge(v, 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    /** The catalog again after a server said which items it has: built-in, then guesses for the rest. */
    private synchronized void rebuildCatalog() {
        Set<String> all = availableItems();
        if (all == null) {
            catalog = builtIn;
            guessed = Set.of();
            return;
        }
        Map<String, Item> merged = new LinkedHashMap<>(builtIn);
        Set<String> guesses = new java.util.HashSet<>();
        for (String id : new java.util.TreeSet<>(all)) {
            if (!merged.containsKey(id)) {
                Item item = guess(id);
                if (item != null) {
                    merged.put(id, item);
                    guesses.add(id);
                }
            }
        }
        if (!guesses.isEmpty() && !guesses.equals(guessed)) {
            plugin.logger().info("The shop has {} items newer than its built-in list (priced like similar items; the "
                    + "settings owner can change them on the Shop page): {}", guesses.size(), String.join(", ", new java.util.TreeSet<>(guesses)));
        }
        catalog = Collections.unmodifiableMap(merged);
        guessed = Set.copyOf(guesses);
    }

    public boolean open() {
        PluginConfig c = plugin.config();
        return c.fadEnabled() && c.shopEnabled();
    }

    /** Items any server with the backend plugin has said it knows, or null if none has said yet. */
    public Set<String> availableItems() {
        if (serverItems.isEmpty()) {
            return null;
        }
        Set<String> all = new java.util.HashSet<>();
        serverItems.values().forEach(all::addAll);
        return all;
    }

    /** Items each server knows, by server name. */
    Map<String, Set<String>> serverItems() {
        return serverItems;
    }

    void serverItems(String server, Set<String> items) {
        serverItems.put(server, Set.copyOf(items));
        rebuildCatalog();
    }

    /** One item's price in hundredths of a credit, after the global and the item's own percentage. */
    public long unitCents(Item item, ShopOverride override) {
        long percent = (long) plugin.config().shopPricePercent() * (override == null ? 100 : override.percent());
        return Math.max(1, Math.round(item.cents() * percent / 10_000.0));
    }

    /** The chest the order comes in. Always sold for orders, even if the chest item itself is turned off. */
    public long chestCents(Map<String, ShopOverride> overrides) {
        Item chest = catalog.get(CHEST);
        return chest == null ? 100 : unitCents(chest, overrides.get(CHEST));
    }

    /** A shulker box to deliver in (always sold for orders, like the chest). */
    public long shulkerCents(Map<String, ShopOverride> overrides) {
        Item box = builtIn.get("shulker_box");
        return box == null ? 0 : unitCents(box, overrides.get("shulker_box"));
    }

    /** What the container adds to an order: its price, or nothing when it goes straight into the inventory. */
    public long containerCents(Container container, Map<String, ShopOverride> overrides) {
        return switch (container) {
            case CHEST -> chestCents(overrides);
            case SHULKER -> shulkerCents(overrides);
            case DIRECT -> 0;
        };
    }

    /** Whole credits for an order: every stack plus the chest, rounded up. */
    public static long total(long cents) {
        return (cents + 99) / 100;
    }

    /**
     * Pays for an order and has it delivered: to the buyer's own Minecraft character, or as a gift to {@code giftTo}
     * (another player who is online right now; the buyer doesn't need to be). Blocks until the server answers
     * (a few seconds at most), so call it off the network threads.
     */
    public Result checkout(LinkedAccount buyer, UUID giftTo, List<Line> lines, String actor) throws SQLException {
        return checkout(buyer, giftTo, lines, Container.CHEST, actor);
    }

    public Result checkout(LinkedAccount buyer, UUID giftTo, List<Line> lines, Container container, String actor)
            throws SQLException {
        if (!open()) {
            return fail("The shop is closed.");
        }
        if (lines.isEmpty()) {
            return fail("Put something in the chest first.");
        }
        if (lines.size() > CHEST_SLOTS) {
            return fail("A chest only has " + CHEST_SLOTS + " slots.");
        }
        Map<String, ShopOverride> overrides = plugin.database().shopOverrides();
        long cents = containerCents(container, overrides);
        for (Line line : lines) {
            Item item = catalog.get(line.id());
            ShopOverride override = overrides.get(line.id());
            if (item == null || override != null && override.disabled()) {
                return fail(name(line.id()) + " isn't for sale.");
            }
            if (line.amount() < 1 || line.amount() > item.stack()) {
                return fail(name(line.id()) + ": a slot holds 1 to " + item.stack() + ".");
            }
            cents += unitCents(item, override) * line.amount();
        }
        long cost = total(cents);

        boolean gift = giftTo != null;
        if (gift && giftTo.equals(buyer.uuid())) {
            gift = false;
        }
        UUID receiverId = gift ? giftTo : buyer.uuid();
        Optional<Player> online = plugin.proxy().getPlayer(buyer.uuid());
        Optional<Player> receiver = plugin.proxy().getPlayer(receiverId);
        Optional<ServerConnection> connection = receiver.flatMap(Player::getCurrentServer);
        if (connection.isEmpty()) {
            return fail(gift ? "Gifts can only go to players who are on the Minecraft server right now."
                    : "Join the Minecraft server first: the chest is put straight into your inventory.");
        }
        String receiverName = receiver.get().getUsername();
        String server = connection.get().getServerInfo().getName();
        if (!community.servers().containsKey(server)) {
            return fail((gift ? receiverName + " is on " + server : "The server you're on (" + server + ")")
                    + ", which can't deliver orders" + (gift ? "." : ". Move to another server, or ask an admin to install "
                    + "DiscordLinkedBackend there."));
        }
        if (container != Container.CHEST && !plugin.servers().deliversContainers(server)) {
            return fail((gift ? receiverName + " is on " + server : "You're on " + server) + ", whose DiscordLinkedBackend "
                    + "is too old to deliver " + (container == Container.SHULKER ? "in a shulker box" : "straight into an inventory")
                    + " (it needs 0.32.0). Choose a chest, or update it.");
        }
        Set<String> known = serverItems.get(server);
        if (known != null) {
            for (Line line : lines) {
                if (!known.contains(line.id())) {
                    return fail(name(line.id()) + " doesn't exist in " + server + "'s Minecraft version.");
                }
            }
        }
        if (!checkingOut.add(buyer.uuid())) {
            return fail("Your previous order is still on its way.");
        }
        try {
            String summary = summary(lines) + (gift ? " (gift to " + receiverName + ")" : "");
            PluginConfig c = plugin.config();
            Market market = community.market();
            Database.Charge charge = plugin.database().chargeCredits(buyer.uuid(), buyer.minecraftName(), cost,
                    market.shopRate(buyer.uuid()), "SHOP", summary, actor);
            if (!charge.ok()) {
                return fail(market.shortOf(buyer.uuid(), "This", cost));
            }
            long balance = charge.balance();
            String exchanged = market.exchangedNote(charge);
            String title = gift ? "Gift from " + buyer.minecraftName() : "Shop order";
            String outcome = deliver(connection.get(), receiverId, lines, title, container);
            String stacks = lines.size() + (lines.size() == 1 ? " stack" : " stacks");
            if (outcome.equals("ok") || outcome.startsWith("ok:")) {
                // "ok:dropped:N": straight into the inventory, N stacks didn't fit and lie at their feet.
                int dropped = outcome.startsWith("ok:dropped:") ? Integer.parseInt(outcome.substring(11)) : 0;
                String suffix = switch (container) {
                    case CHEST -> "";
                    case SHULKER -> "-shulker";
                    case DIRECT -> "-direct";
                };
                plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.SHOP_PURCHASE,
                        (gift ? "gifted " : "bought ") + summary + " " + container.described + " for " + c.credits(cost) + exchanged
                                + (dropped > 0 ? " (" + dropped + " dropped at their feet: no room)" : "")
                                + ", now " + c.credits(balance), actor, server);
                if (dropped > 0) {
                    receiver.get().sendMessage(plugin.messages().minecraft("minecraft.shop.dropped",
                            "count", String.valueOf(dropped)));
                }
                String droppedNote = dropped > 0 ? " " + dropped + (dropped == 1 ? " stack didn't" : " stacks didn't")
                        + " fit and " + (dropped == 1 ? "was" : "were") + " dropped at " + (gift ? "their" : "your") + " feet." : "";
                if (gift) {
                    receiver.get().sendMessage(plugin.messages().minecraft("minecraft.shop.gift-received" + suffix,
                            "from", buyer.minecraftName(), "stacks", stacks));
                    online.ifPresent(player -> player.sendMessage(plugin.messages().minecraft("minecraft.shop.gift-sent" + suffix,
                            "to", receiverName, "stacks", stacks, "cost", c.credits(cost))));
                    return new Result(true, "Delivered! " + receiverName + " got your gift." + droppedNote + exchanged, cost, balance);
                }
                receiver.get().sendMessage(plugin.messages().minecraft("minecraft.shop.delivered" + suffix,
                        "stacks", stacks, "cost", c.credits(cost)));
                String where = switch (container) {
                    case CHEST -> "The chest is in your inventory.";
                    case SHULKER -> "The shulker box is in your inventory.";
                    case DIRECT -> "It's in your inventory.";
                };
                return new Result(true, "Delivered! " + where + droppedNote + exchanged, cost, balance);
            }
            String whose = gift ? receiverName + "'s" : "your";
            String reason = switch (outcome) {
                case "full" -> whose + " inventory has no free slot for the " + (container == Container.SHULKER ? "shulker box" : "chest");
                case "timeout" -> "the server didn't answer";
                case "offline" -> gift ? receiverName + " left the server" : "you left the server";
                case "expired" -> "the server was too busy";
                default -> outcome.startsWith("unknown:") ? name(outcome.substring(8)) + " doesn't exist on that server"
                        : outcome.startsWith("stack:") ? name(outcome.substring(6)) + " doesn't stack that high on that server"
                        : outcome;
            };
            // Credits back, and microcredits that were exchanged for it back as microcredits.
            long refunded = plugin.database().refundCharge(buyer.uuid(), buyer.minecraftName(), cost, charge, "SHOP",
                    "refund: " + reason, actor);
            plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.SHOP_PURCHASE,
                    "declined (" + reason + "), refunded " + c.credits(cost) + ": " + summary, actor, server);
            online.ifPresent(player -> player.sendMessage(plugin.messages().minecraft("minecraft.shop.declined",
                    "reason", reason)));
            return new Result(false, "Not delivered: " + reason + ". You weren't charged.", cost, refunded);
        } finally {
            checkingOut.remove(buyer.uuid());
        }
    }

    private String deliver(ServerConnection connection, UUID player, List<Line> lines, String title, Container container) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<String> outcome = new CompletableFuture<>();
        deliveries.put(id, new Delivery(player, outcome));
        try {
            community.bridge().shop(connection, id, lines, title, container.id);
            return outcome.get(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "timeout";
        } catch (ExecutionException e) {
            return "timeout";
        } finally {
            deliveries.remove(id);
        }
    }

    /** The server's answer to a delivery. Only the receiving player's own server connection can answer. */
    void delivered(UUID carrier, String id, String outcome) {
        Delivery delivery = deliveries.get(id);
        if (delivery != null && delivery.player().equals(carrier)) {
            delivery.outcome().complete(outcome);
        }
    }

    private static String summary(List<Line> lines) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        lines.forEach(line -> totals.merge(line.id(), line.amount(), Integer::sum));
        List<String> parts = new ArrayList<>();
        totals.forEach((id, amount) -> parts.add(amount + " × " + name(id)));
        return String.join(", ", parts);
    }

    /** "oak_log" -> "Oak Log". */
    public static String name(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private static Result fail(String message) {
        return new Result(false, message, 0, -1);
    }
}
