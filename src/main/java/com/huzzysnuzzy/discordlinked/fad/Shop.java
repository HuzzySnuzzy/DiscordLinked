package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
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
    /** The server gives up delivering after 8 seconds, so by 12 nothing can still arrive and a refund is safe. */
    private static final long DELIVERY_TIMEOUT_SECONDS = 12;

    public record Item(String id, String tab, int stack, long cents) {
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
    private final Map<String, Item> catalog;
    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();
    private final Set<UUID> checkingOut = ConcurrentHashMap.newKeySet();
    /** Items each server knows (from DiscordLinkedFAD), so items from newer Minecraft versions aren't offered. */
    private final Map<String, Set<String>> serverItems = new ConcurrentHashMap<>();

    Shop(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
        this.catalog = loadCatalog();
    }

    private static Map<String, Item> loadCatalog() {
        try (InputStream in = Shop.class.getResourceAsStream("/shop-items.json")) {
            if (in == null) {
                throw new IllegalStateException("shop-items.json missing from the plugin jar");
            }
            DataArray items = DataObject.fromJson(in).getArray("items");
            Map<String, Item> catalog = new LinkedHashMap<>();
            for (int i = 0; i < items.length(); i++) {
                DataObject item = items.getObject(i);
                catalog.put(item.getString("id"), new Item(item.getString("id"), item.getString("tab"),
                        item.getInt("stack"), item.getLong("price")));
            }
            return Collections.unmodifiableMap(catalog);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read shop-items.json", e);
        }
    }

    public Map<String, Item> catalog() {
        return catalog;
    }

    public boolean open() {
        PluginConfig c = plugin.config();
        return c.fadEnabled() && c.shopEnabled();
    }

    /** Items any server with DiscordLinkedFAD has said it knows, or null if none has said yet. */
    public Set<String> availableItems() {
        if (serverItems.isEmpty()) {
            return null;
        }
        Set<String> all = new java.util.HashSet<>();
        serverItems.values().forEach(all::addAll);
        return all;
    }

    void serverItems(String server, Set<String> items) {
        serverItems.put(server, Set.copyOf(items));
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

    /** Whole credits for an order: every stack plus the chest, rounded up. */
    public static long total(long cents) {
        return (cents + 99) / 100;
    }

    /**
     * Pays for an order and has it delivered to the buyer's Minecraft character. Blocks until the server answers
     * (a few seconds at most), so call it off the network threads.
     */
    public Result checkout(LinkedAccount buyer, List<Line> lines, String actor) throws SQLException {
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
        long cents = chestCents(overrides);
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

        Optional<Player> online = plugin.proxy().getPlayer(buyer.uuid());
        Optional<ServerConnection> connection = online.flatMap(Player::getCurrentServer);
        if (connection.isEmpty()) {
            return fail("Join the Minecraft server first: the chest is put straight into your inventory.");
        }
        String server = connection.get().getServerInfo().getName();
        if (!community.servers().containsKey(server)) {
            return fail("The server you're on (" + server + ") can't deliver orders. Move to another server, "
                    + "or ask an admin to install DiscordLinkedFAD there.");
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
            String summary = summary(lines);
            PluginConfig c = plugin.config();
            long balance = plugin.database().addCredits(buyer.uuid(), buyer.minecraftName(), -cost, "SHOP", summary, actor);
            if (balance < 0) {
                long has = plugin.database().creditAccount(buyer.uuid()).map(a -> a.credits()).orElse(0L);
                return fail("This costs " + c.credits(cost) + " but you have " + c.credits(has) + ".");
            }
            String outcome = deliver(connection.get(), buyer.uuid(), lines);
            if ("ok".equals(outcome)) {
                plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.SHOP_PURCHASE,
                        "bought " + summary + " in a chest for " + c.credits(cost) + ", now " + c.credits(balance), actor);
                online.get().sendMessage(plugin.messages().minecraft("minecraft.shop.delivered",
                        "stacks", lines.size() + (lines.size() == 1 ? " stack" : " stacks"), "cost", c.credits(cost)));
                return new Result(true, "Delivered! The chest is in your inventory.", cost, balance);
            }
            String reason = switch (outcome) {
                case "full" -> "your inventory has no free slot for the chest";
                case "timeout" -> "the server didn't answer";
                case "offline" -> "you left the server";
                case "expired" -> "the server was too busy";
                default -> outcome.startsWith("unknown:") ? name(outcome.substring(8)) + " doesn't exist on that server"
                        : outcome.startsWith("stack:") ? name(outcome.substring(6)) + " doesn't stack that high on that server"
                        : outcome;
            };
            long refunded = plugin.database().addCredits(buyer.uuid(), buyer.minecraftName(), cost, "SHOP",
                    "refund: " + reason, actor, false);
            plugin.audit().log(buyer.uuid(), buyer.minecraftName(), buyer.discordId(), EventTypes.SHOP_PURCHASE,
                    "declined (" + reason + "), refunded " + c.credits(cost) + ": " + summary, actor);
            online.ifPresent(player -> player.sendMessage(plugin.messages().minecraft("minecraft.shop.declined",
                    "reason", reason)));
            return new Result(false, "Not delivered: " + reason + ". You weren't charged.", cost, refunded);
        } finally {
            checkingOut.remove(buyer.uuid());
        }
    }

    private String deliver(ServerConnection connection, UUID player, List<Line> lines) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<String> outcome = new CompletableFuture<>();
        deliveries.put(id, new Delivery(player, outcome));
        try {
            community.bridge().shop(connection, id, lines);
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

    /** The server's answer to a delivery. Only the buyer's own server connection can answer. */
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
