package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The "discordlinked:fad" channel to DiscordLinkedFAD on the Minecraft servers. Messages are only accepted from
 * server connections (a player's client can't send them) and always travel through the player they're about.
 * Database work happens off the network thread.
 */
final class FadBridge {

    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("discordlinked", "fad");
    private static final int MAX_JSON_BYTES = 1 << 20;

    private final DiscordLinked plugin;
    private final Community community;

    FadBridge(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        // Never pass these on, and never accept them from a player's client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        String server = connection.getServerInfo().getName();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            switch (in.readUTF()) {
                case "HELLO" -> {
                    String version = in.readUTF();
                    String json = new String(readBlob(in), StandardCharsets.UTF_8);
                    later(() -> {
                        try {
                            community.hello(server, version, json);
                            send(connection, out -> out.writeUTF("HELLO_OK"));
                            community.cosmetics().serverReady(server);
                        } catch (IllegalArgumentException e) {
                            plugin.logger().warn("DiscordLinkedFAD on {} sent an unreadable interactions list: {}",
                                    server, e.getMessage());
                        }
                    });
                }
                case "INTERACT" -> {
                    String id = in.readUTF();
                    String action = in.readUTF();
                    String target = in.readUTF();
                    String line = in.readUTF();
                    String format = in.readUTF();
                    String nameFormat = in.readUTF();
                    UUID targetId = target.isEmpty() ? null : UUID.fromString(target);
                    later(() -> {
                        boolean shown = community.interactFromGame(player, server, action, targetId, line, format, nameFormat);
                        reply(connection, shown ? "OK" : "ACK", id);
                    });
                }
                case "CREDITS" -> {
                    String id = in.readUTF();
                    String query = in.readUTF().trim();
                    later(() -> {
                        community.credits(player, query);
                        reply(connection, "ACK", id);
                    });
                }
                case "TOGGLE" -> {
                    String id = in.readUTF();
                    boolean enabled = in.readBoolean();
                    later(() -> {
                        community.toggle(player, enabled);
                        reply(connection, "ACK", id);
                    });
                }
                case "AFK" -> community.setAfk(player.getUniqueId(), in.readBoolean());
                case "SHOP_RESULT" -> community.shop().delivered(player.getUniqueId(), in.readUTF(), in.readUTF());
                case "ITEMS" -> {
                    int count = in.readInt();
                    if (count < 0 || count > 10_000) {
                        throw new IOException("bad item count " + count);
                    }
                    java.util.Set<String> items = new java.util.HashSet<>();
                    for (int i = 0; i < count; i++) {
                        items.add(in.readUTF());
                    }
                    community.shop().serverItems(server, items);
                    // Sent right after HELLO, so this server now gets the sell prices for exactly its items.
                    later(() -> community.market().sendPrices(connection));
                }
                // Items the server already took from the player (/sell, /sellhand): pay for them.
                case "SELL" -> {
                    String id = in.readUTF();
                    List<Market.Line> lines = readLines(in);
                    later(() -> {
                        Market.Sale sale = community.market().sell(player, server, lines);
                        send(connection, out -> {
                            out.writeUTF("SOLD");
                            out.writeUTF(id);
                            out.writeLong(sale.paid());
                            writeLines(out, sale.refused());
                        });
                    });
                }
                case "MCT" -> {
                    String id = in.readUTF();
                    later(() -> {
                        community.market().topFromGame(player);
                        reply(connection, "ACK", id);
                    });
                }
                case "EXCHANGE" -> {
                    String id = in.readUTF();
                    String amount = in.readUTF().trim();
                    later(() -> {
                        community.market().exchangeFromGame(player, amount);
                        reply(connection, "ACK", id);
                    });
                }
                case "ADV" -> {
                    String key = in.readUTF();
                    String title = in.readUTF();
                    String frame = in.readUTF();
                    later(() -> community.advancement(player, server, key, title, frame));
                }
                default -> { }
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.logger().warn("Bad DiscordLinkedFAD message from {}: {}", server, e.getMessage());
        }
    }

    /**
     * A new server has its own AFK detection; start out active there. If that server hasn't introduced itself
     * since the proxy started, ask it to, once the player has finished joining.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        community.clearAfk(player.getUniqueId());
        String server = event.getServer().getServerInfo().getName();
        if (!community.servers().containsKey(server)) {
            plugin.proxy().getScheduler().buildTask(plugin, () -> player.getCurrentServer()
                    .filter(connection -> connection.getServerInfo().getName().equals(server))
                    .ifPresent(connection -> send(connection, out -> out.writeUTF("SYNC"))))
                    .delay(2, TimeUnit.SECONDS).schedule();
        }
        community.cosmetics().joined(player, server);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        community.clearAfk(event.getPlayer().getUniqueId());
    }

    /** Plays an interaction's sound and particles at a player, for interactions started on Discord. */
    void effects(Player player, String action) {
        player.getCurrentServer().ifPresent(connection -> send(connection, out -> {
            out.writeUTF("FX");
            out.writeUTF(action);
        }));
    }

    /**
     * Asks the receiving player's server to put a Shop order (as one chest named {@code title}) in their inventory.
     * The title comes last, so DiscordLinkedFAD 0.13 (which doesn't read it) still understands the message.
     */
    void shop(ServerConnection connection, String id, java.util.List<Shop.Line> lines, String title) {
        send(connection, out -> {
            out.writeUTF("SHOP");
            out.writeUTF(id);
            out.writeInt(lines.size());
            for (Shop.Line line : lines) {
                out.writeUTF(line.id());
                out.writeInt(line.amount());
            }
            out.writeUTF(title);
        });
    }

    /**
     * What a player has in use: their prefix (with &amp; colour codes), trail particle and from when KeepInventory
     * works (epoch millis, 0 = off). Empty strings switch a prefix or trail off.
     */
    void cosmetics(ServerConnection connection, UUID player, String prefix, String trail, String trailStyle,
                   String aura, String auraStyle, long keepInventoryFrom) {
        send(connection, out -> {
            out.writeUTF("COSMETICS");
            out.writeUTF(player.toString());
            out.writeUTF(prefix);
            out.writeUTF(trail);
            out.writeLong(keepInventoryFrom);
            // Added in 0.20 at the end, so DiscordLinkedFAD 0.18 (which stops reading here) keeps working.
            out.writeUTF(trailStyle);
            out.writeUTF(aura);
            out.writeUTF(auraStyle);
        });
    }

    /** Most prices per message: plugin messages to a server can't be much bigger than 32 KB. */
    private static final int PRICES_PER_MESSAGE = 400;

    /**
     * Sends a server the current sell prices (for /worth and the /sell menu), split over a few messages. An empty
     * list with {@code open} false means selling is closed.
     */
    void prices(ServerConnection connection, boolean open, String symbol, List<Market.Price> prices) {
        int parts = Math.max(1, (prices.size() + PRICES_PER_MESSAGE - 1) / PRICES_PER_MESSAGE);
        for (int part = 0; part < parts; part++) {
            List<Market.Price> chunk = prices.subList(part * PRICES_PER_MESSAGE,
                    Math.min(prices.size(), (part + 1) * PRICES_PER_MESSAGE));
            int index = part;
            send(connection, out -> {
                out.writeUTF("PRICES");
                out.writeInt(index);
                out.writeInt(parts);
                out.writeBoolean(open);
                out.writeUTF(symbol);
                out.writeInt(chunk.size());
                for (Market.Price price : chunk) {
                    out.writeUTF(price.id());
                    out.writeLong(price.now());
                    out.writeLong(price.normal());
                }
            });
        }
    }

    private static List<Market.Line> readLines(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 2_000) {
            throw new IOException("bad line count " + count);
        }
        List<Market.Line> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(new Market.Line(in.readUTF(), in.readInt()));
        }
        return lines;
    }

    private static void writeLines(DataOutputStream out, List<Market.Line> lines) throws IOException {
        out.writeInt(lines.size());
        for (Market.Line line : lines) {
            out.writeUTF(line.id());
            out.writeInt(line.amount());
        }
    }

    // ---- Helpers ----

    private void later(Runnable task) {
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.logger().error("DiscordLinkedFAD message failed", e);
            }
        }).schedule();
    }

    private void reply(ServerConnection connection, String type, String id) {
        send(connection, out -> {
            out.writeUTF(type);
            out.writeUTF(id);
        });
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static void send(ServerConnection connection, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            body.write(out);
            connection.sendPluginMessage(CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_JSON_BYTES) {
            throw new IOException("bad length " + length);
        }
        return in.readNBytes(length);
    }
}
