package com.huzzysnuzzy.discordlinked.fad;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Talks to DiscordLinked on the Velocity proxy over the "discordlinked:fad" channel. Plugin messages can only
 * travel through a connected player, so everything is sent through the player it's about. The proxy only
 * accepts this channel from servers, never from players' clients.
 *
 * <p>Requests that need an answer (interactions, /credits, /interactions on|off) wait a few seconds for it; if
 * none comes, the player is told the proxy plugin is missing or outdated instead of nothing happening.
 */
final class ProxyLink implements PluginMessageListener {

    static final String CHANNEL = "discordlinked:fad";
    private static final long TIMEOUT_SECONDS = 4;

    /** A request waiting for the proxy. {@code action} and {@code target} are only set for interactions. */
    private record Pending(UUID player, Action action, UUID target, ScheduledTask timeout) {
    }

    private final DiscordLinkedFAD plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Whether the proxy has this server's current list of interactions. */
    private volatile boolean synced;

    ProxyLink(DiscordLinkedFAD plugin) {
        this.plugin = plugin;
    }

    boolean synced() {
        return synced;
    }

    /** After a reload: send the new list with the next player that joins, or right away through anyone online. */
    void resync() {
        synced = false;
        Bukkit.getOnlinePlayers().stream().findFirst().ifPresent(this::hello);
    }

    // ---- To the proxy ----

    /** Introduces this server and sends its interactions, so Discord can use and show the same ones. */
    void hello(Player carrier) {
        byte[] json = plugin.settings().toJson(plugin.getPluginMeta().getVersion()).getBytes(StandardCharsets.UTF_8);
        send(carrier, out -> {
            out.writeUTF("HELLO");
            out.writeUTF(plugin.getPluginMeta().getVersion());
            out.writeInt(json.length);
            out.write(json);
        });
        // Every item this Minecraft version has, so the website's Shop only sells what exists here.
        List<String> items = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem() && !material.isAir()) {
                items.add(material.getKey().getKey());
            }
        }
        send(carrier, out -> {
            out.writeUTF("ITEMS");
            out.writeInt(items.size());
            for (String item : items) {
                out.writeUTF(item);
            }
        });
    }

    /**
     * Asks the proxy to show an interaction everywhere. When it agrees (the target hasn't turned interactions off),
     * the effects play here.
     */
    void interact(Player actor, Player target, Action action, String line) {
        FadSettings settings = plugin.settings();
        String id = expect(actor, action, target == null ? null : target.getUniqueId());
        send(actor, out -> {
            out.writeUTF("INTERACT");
            out.writeUTF(id);
            out.writeUTF(action.name());
            out.writeUTF(target == null ? "" : target.getUniqueId().toString());
            out.writeUTF(line);
            out.writeUTF(settings.format);
            out.writeUTF(settings.nameFormat);
        });
    }

    /** /credits, /credits <player> or /credits top. The proxy replies to the player in chat. */
    void credits(Player player, String query) {
        String id = expect(player, null, null);
        send(player, out -> {
            out.writeUTF("CREDITS");
            out.writeUTF(id);
            out.writeUTF(query);
        });
    }

    /** /interactions on|off. The proxy remembers it (for Discord too) and replies in chat. */
    void toggle(Player player, boolean enabled) {
        String id = expect(player, null, null);
        send(player, out -> {
            out.writeUTF("TOGGLE");
            out.writeUTF(id);
            out.writeBoolean(enabled);
        });
    }

    void afk(Player player, boolean afk) {
        send(player, out -> {
            out.writeUTF("AFK");
            out.writeBoolean(afk);
        });
    }

    void advancement(Player player, String key, String title, String frame) {
        send(player, out -> {
            out.writeUTF("ADV");
            out.writeUTF(key);
            out.writeUTF(title.length() > 200 ? title.substring(0, 200) : title);
            out.writeUTF(frame);
        });
    }

    /** /mct. The proxy replies in chat. */
    void microTop(Player player) {
        String id = expect(player, null, null);
        send(player, out -> {
            out.writeUTF("MCT");
            out.writeUTF(id);
        });
    }

    /** /exchange <amount|all>. The proxy replies in chat. */
    void exchange(Player player, String amount) {
        String id = expect(player, null, null);
        send(player, out -> {
            out.writeUTF("EXCHANGE");
            out.writeUTF(id);
            out.writeUTF(amount);
        });
    }

    /** A sale waiting for the proxy to say what it paid. */
    private record PendingSale(UUID player, Map<String, Integer> lines, ScheduledTask timeout) {
    }

    private final Map<String, PendingSale> sales = new ConcurrentHashMap<>();

    /**
     * Sells items this server already took from the player. The proxy pays and tells them in chat, then sends back
     * anything it wouldn't buy, which the player gets back.
     */
    void sell(Player player, Map<String, Integer> lines) {
        String id = UUID.randomUUID().toString();
        ScheduledTask timeout = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            PendingSale sale = sales.remove(id);
            if (sale != null) {
                plugin.getLogger().warning("The proxy didn't confirm a sale by " + player.getName() + ": " + sale.lines());
                Player online = Bukkit.getPlayer(sale.player());
                if (online != null) {
                    online.sendMessage(plugin.settings().message("sell-no-answer"));
                }
            }
        }, 15, TimeUnit.SECONDS);
        sales.put(id, new PendingSale(player.getUniqueId(), lines, timeout));
        send(player, out -> {
            out.writeUTF("SELL");
            out.writeUTF(id);
            out.writeInt(lines.size());
            for (Map.Entry<String, Integer> line : lines.entrySet()) {
                out.writeUTF(line.getKey());
                out.writeInt(line.getValue());
            }
        });
    }

    // ---- From the proxy ----

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            switch (in.readUTF()) {
                case "HELLO_OK" -> synced = true;
                // The proxy restarted (or lost our list) and wants to know about this server again.
                case "SYNC" -> hello(carrier);
                case "OK" -> {
                    Pending request = finish(in.readUTF());
                    if (request != null && request.action() != null) {
                        Player actor = Bukkit.getPlayer(request.player());
                        Player target = request.target() == null ? null : Bukkit.getPlayer(request.target());
                        if (actor != null) {
                            Effects.play(plugin, request.action(), actor, target);
                        }
                    }
                }
                // The proxy already told the player why (e.g. the target turned interactions off).
                case "ACK" -> finish(in.readUTF());
                // A website Shop order for this player: deliver it as a chest and say how it went.
                case "SHOP" -> {
                    long receivedAt = System.currentTimeMillis();
                    String id = in.readUTF();
                    int count = in.readInt();
                    if (count < 1 || count > 54) {
                        return;
                    }
                    List<ShopDelivery.Line> lines = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        lines.add(new ShopDelivery.Line(in.readUTF(), in.readInt()));
                    }
                    // The chest's name ("Shop order", "Gift from ..."); older proxies don't send one.
                    String title = in.available() > 0 ? in.readUTF() : "Shop order";
                    ShopDelivery.deliver(plugin, carrier, lines, title, receivedAt, outcome -> send(carrier, out -> {
                        out.writeUTF("SHOP_RESULT");
                        out.writeUTF(id);
                        out.writeUTF(outcome);
                    }));
                }
                // Sell prices, in a few parts; also says whether selling is open.
                case "PRICES" -> {
                    int part = in.readInt();
                    int parts = in.readInt();
                    boolean open = in.readBoolean();
                    String symbol = in.readUTF();
                    int count = in.readInt();
                    if (part < 0 || part >= parts || parts > 100 || count < 0 || count > 10_000) {
                        return;
                    }
                    Map<String, SellPrices.Price> chunk = new java.util.HashMap<>();
                    for (int i = 0; i < count; i++) {
                        chunk.put(in.readUTF(), new SellPrices.Price(in.readLong(), in.readLong()));
                    }
                    plugin.sellPrices().receive(part, parts, open, symbol, chunk);
                }
                // What a sale paid, and what the proxy wouldn't buy (given back).
                case "SOLD" -> {
                    PendingSale sale = sales.remove(in.readUTF());
                    long paid = in.readLong();
                    int count = in.readInt();
                    List<ItemStack> refused = new ArrayList<>();
                    for (int i = 0; i < count && i < 2_000; i++) {
                        Material material = Material.matchMaterial(in.readUTF());
                        int amount = in.readInt();
                        if (material == null || !material.isItem() || material.isAir()) {
                            continue;
                        }
                        while (amount > 0) {
                            int stack = Math.min(amount, material.getMaxStackSize());
                            refused.add(new ItemStack(material, stack));
                            amount -= stack;
                        }
                    }
                    if (sale == null) {
                        return;
                    }
                    sale.timeout().cancel();
                    Player player = Bukkit.getPlayer(sale.player());
                    if (player != null) {
                        player.getScheduler().run(plugin, task -> {
                            SellMenu.giveBack(player, refused);
                            if (paid > 0) {
                                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                            }
                        }, null);
                    }
                }
                // What a player bought on the website and uses: prefix, trail, KeepInventory.
                case "COSMETICS" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    Cosmetics.State state = new Cosmetics.State(in.readUTF(), in.readUTF(), in.readLong());
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        player.getScheduler().run(plugin, task -> plugin.cosmetics().apply(player, state), null);
                    }
                }
                // Someone on Discord picked this player: play the effects where they are.
                case "FX" -> {
                    Action action = plugin.settings().actions.get(in.readUTF());
                    if (action != null) {
                        Effects.play(plugin, action, carrier, null);
                    }
                }
                default -> { }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Bad message from the proxy: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    private String expect(Player player, Action action, UUID target) {
        String id = UUID.randomUUID().toString();
        ScheduledTask timeout = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            if (pending.remove(id) != null) {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online != null) {
                    online.sendMessage(plugin.settings().message("no-reply"));
                }
            }
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pending.put(id, new Pending(player.getUniqueId(), action, target, timeout));
        return id;
    }

    private Pending finish(String id) {
        Pending request = pending.remove(id);
        if (request != null) {
            request.timeout().cancel();
        }
        return request;
    }

    void cancelAll() {
        pending.values().forEach(p -> p.timeout().cancel());
        pending.clear();
        sales.values().forEach(p -> p.timeout().cancel());
        sales.clear();
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    /** Sends on the player's own thread (on Folia the one that owns their region). */
    private void send(Player carrier, Body body) {
        byte[] bytes;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            body.write(out);
            bytes = buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (Bukkit.isOwnedByCurrentRegion(carrier)) {
            carrier.sendPluginMessage(plugin, CHANNEL, bytes);
        } else {
            carrier.getScheduler().run(plugin, task -> carrier.sendPluginMessage(plugin, CHANNEL, bytes), null);
        }
    }
}
