package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.servers.BackendServers;
import com.huzzysnuzzy.discordlinked.storage.Home;
import com.huzzysnuzzy.discordlinked.update.ProxyUpdater;
import com.huzzysnuzzy.discordlinked.update.Updater;
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
 * The "discordlinked:fad" channel to the backend plugin on the Minecraft servers (DiscordLinkedBackend, or
 * DiscordLinkedFAD from before the merge). Messages are only accepted from server connections (a player's client
 * can't send them) and always travel through the player they're about. Database work happens off the network
 * thread. Message types this proxy doesn't know are ignored, so newer backends can add some.
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
                    // Noted here, in the order the messages came, so the STATUS that follows is seen after it.
                    plugin.servers().hello(server, version);
                    later(() -> {
                        try {
                            community.hello(server, version, json);
                            helloOk(connection, server);
                            updates(connection);
                            community.cosmetics().serverReady(server);
                        } catch (IllegalArgumentException e) {
                            plugin.logger().warn("The backend plugin on {} sent an unreadable interactions list: {}",
                                    server, e.getMessage());
                        }
                    });
                }
                // DiscordLinkedBackend, after HELLO_OK and then every 30 seconds. Fields are only ever added at the
                // end: ones an older backend doesn't send stay unknown, newer ones are left unread.
                case "STATUS" -> {
                    String name = clip(in.readUTF());
                    String version = clip(in.readUTF());
                    String minecraft = in.available() > 0 ? clip(in.readUTF()) : "";
                    boolean folia = in.available() > 0 && in.readBoolean();
                    int online = in.available() > 0 ? in.readInt() : -1;
                    int max = in.available() > 0 ? in.readInt() : -1;
                    double tps = in.available() > 0 ? in.readDouble() : -1;
                    double mspt = in.available() > 0 ? in.readDouble() : -1;
                    Boolean spy = in.available() > 0 ? in.readBoolean() : null;
                    BackendServers.Update update = BackendServers.Update.read(in);
                    plugin.servers().status(server, new BackendServers.Status(name, version, minecraft, folia, online,
                            max, tps, mspt, spy, update));
                }
                // DiscordLinkedBackend 0.26.0: how far an MC data reset got on this server, and a player's summary.
                case "MCDATA_STATE" -> {
                    String token = in.readUTF();
                    boolean undone = in.readBoolean();
                    int applied = in.readInt();
                    int restored = in.readInt();
                    later(() -> plugin.mcData().state(server, token, undone, applied, restored));
                }
                case "MCSUMMARY" -> {
                    String json = new String(readBlob(in), StandardCharsets.UTF_8);
                    UUID uuid = player.getUniqueId();
                    later(() -> plugin.mcData().summary(uuid, server, json));
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
                // DiscordLinkedBackend 0.30.0: homes. The proxy answers in chat (and HOME_TP for /home).
                case "HOME_SET" -> {
                    String name = in.readUTF();
                    String world = in.readUTF();
                    double x = in.readDouble();
                    double y = in.readDouble();
                    double z = in.readDouble();
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    later(() -> community.homes().set(player, server, name, world, x, y, z, yaw, pitch));
                }
                case "HOME_GO" -> {
                    String query = in.readUTF();
                    later(() -> community.homes().go(player, server, query));
                }
                case "HOME_DEL" -> {
                    String name = in.readUTF();
                    later(() -> community.homes().delete(player, server, name));
                }
                case "HOME_LIST" -> later(() -> community.homes().list(player, server));
                // DiscordLinkedBackend 0.32.0: /spawn, and where a player stands (for /dl setspawn).
                case "SPAWN_GO" -> later(() -> community.homes().goSpawn(player, server));
                case "LOCATED" -> {
                    String id = in.readUTF();
                    String world = in.readUTF();
                    double x = in.readDouble();
                    double y = in.readDouble();
                    double z = in.readDouble();
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    community.homes().located(player, server, id, world, x, y, z, yaw, pitch);
                }
                case "HOME_DONE" -> {
                    String id = in.readUTF();
                    String outcome = in.readUTF();
                    later(() -> community.homes().done(player, id, outcome));
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
            plugin.logger().warn("Bad backend plugin message from {}: {}", server, e.getMessage());
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
        community.homes().joined(player, server);
        community.sidebar().joined(player, server);
    }

    /**
     * The answer to HELLO, through the player who carried it. They may have left or moved on by now: then the
     * server's STATUS comes later (on its timer, or as its answer to the proxy's ping) and its next HELLO, if it
     * still needs one, comes with the next player.
     */
    private void helloOk(ServerConnection connection, String server) {
        try {
            send(connection, out -> out.writeUTF("HELLO_OK"));
        } catch (IllegalStateException e) {
            plugin.logger().debug("Couldn't answer {}'s HELLO, the player left: {}", server, e.getMessage());
        }
    }

    /**
     * The update settings for the backend servers and the newest release the proxy has seen (DiscordLinkedBackend
     * 0.25.0 and newer keeps them; older ones ignore this). After HELLO, and to every server when they change.
     */
    void updates(ServerConnection connection) {
        ProxyUpdater updater = plugin.updates();
        if (updater == null) {
            return;
        }
        Updater.Policy policy = plugin.config().updatePolicy();
        String latest = updater.snapshot().latest();
        try {
            send(connection, out -> {
                out.writeUTF("UPDATES");
                out.writeUTF(policy.mode().key());
                out.writeUTF(plugin.config().backendRestart().key());
                out.writeUTF(policy.timeText());
                out.writeUTF(policy.repository());
                out.writeInt(policy.checkHours());
                out.writeUTF(latest);
            });
        } catch (IllegalStateException e) {
            plugin.logger().debug("Couldn't send the update settings, the player left: {}", e.getMessage());
        }
    }

    /** An MC data reset (or its undo) for this server (see McDataManager). Throws if the player left. */
    void mcData(ServerConnection connection, String token, String kind, String target, String categories,
                long created, String actor) {
        send(connection, out -> {
            out.writeUTF("MCDATA");
            out.writeUTF(token);
            out.writeUTF(kind);
            out.writeUTF(target);
            out.writeUTF(categories);
            out.writeLong(created);
            out.writeUTF(actor.length() > 100 ? actor.substring(0, 100) : actor);
        });
    }

    /** {@link #updates} to every server someone is on (through one player each). Others get it with their HELLO. */
    void updatesToAll() {
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.getCurrentServer().ifPresent(connection -> {
                if (done.add(connection.getServerInfo().getName())) {
                    updates(connection);
                }
            });
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        community.left(event.getPlayer().getUniqueId());
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
    /** {@code container}: chest, shulker or direct (DiscordLinkedBackend 0.32.0+; older ones always use a chest). */
    void shop(ServerConnection connection, String id, java.util.List<Shop.Line> lines, String title, String container) {
        send(connection, out -> {
            out.writeUTF("SHOP");
            out.writeUTF(id);
            out.writeInt(lines.size());
            for (Shop.Line line : lines) {
                out.writeUTF(line.id());
                out.writeInt(line.amount());
            }
            out.writeUTF(title);
            out.writeUTF(container);
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

    /** The on-screen leaderboard as a sidebar (DiscordLinkedBackend 0.31.0+): a title and lines, as JSON components. */
    void sidebar(ServerConnection connection, UUID player, String title, List<String> lines) {
        send(connection, out -> {
            out.writeUTF("SIDEBAR");
            out.writeUTF(player.toString());
            out.writeUTF(title);
            out.writeInt(lines.size());
            for (String line : lines) {
                out.writeUTF(line);
            }
        });
    }

    void sidebarOff(ServerConnection connection, UUID player) {
        send(connection, out -> {
            out.writeUTF("SIDEBAR_OFF");
            out.writeUTF(player.toString());
        });
    }

    /** Asks where the player stands (they answer LOCATED; DiscordLinkedBackend 0.32.0+). */
    void locate(ServerConnection connection, String id) {
        send(connection, out -> {
            out.writeUTF("LOCATE");
            out.writeUTF(id);
        });
    }

    /** A player's home names, for tab completion (their own, and "Name:home" for their group's). */
    void homeNames(ServerConnection connection, UUID player, List<String> names) {
        send(connection, out -> {
            out.writeUTF("HOME_NAMES");
            out.writeUTF(player.toString());
            out.writeInt(names.size());
            for (String name : names) {
                out.writeUTF(name);
            }
        });
    }

    /**
     * /home: wait {@code warmup} seconds (moving or being hurt stops it), then teleport to the home ({@code here}) or
     * just answer "ready" so the proxy moves them to its server. The texts are JSON components.
     */
    void homeTeleport(ServerConnection connection, String id, int warmup, boolean here, Home home, String warmupText,
                      String arrivedText, String movedText, String hurtText, boolean effects) {
        send(connection, out -> {
            out.writeUTF("HOME_TP");
            out.writeUTF(id);
            out.writeInt(warmup);
            out.writeBoolean(here);
            out.writeUTF(home.world());
            out.writeDouble(home.x());
            out.writeDouble(home.y());
            out.writeDouble(home.z());
            out.writeFloat(home.yaw());
            out.writeFloat(home.pitch());
            out.writeUTF(warmupText);
            out.writeUTF(arrivedText);
            out.writeUTF(movedText);
            out.writeUTF(hurtText);
            out.writeBoolean(effects); // read by DiscordLinkedBackend 0.34.0+, ignored before
        });
    }

    /** Where the spawn's particles go on this server ({@code world} empty: its own world spawn), or off. */
    void spawnEffects(ServerConnection connection, boolean on, String world, double x, double y, double z) {
        send(connection, out -> {
            out.writeUTF("SPAWN_FX");
            out.writeBoolean(on);
            out.writeUTF(world);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
        });
    }

    private void later(Runnable task) {
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.logger().error("Backend plugin message failed", e);
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

    /** Plugin names and versions are short; a broken one can't fill the database. */
    private static String clip(String text) {
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_JSON_BYTES) {
            throw new IOException("bad length " + length);
        }
        return in.readNBytes(length);
    }
}
