package com.huzzysnuzzy.discordlinked.spy;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The inventory side of the companion plugin: live inventory views, automatic snapshots (death, join,
 * quit, world change), manual snapshots and restores, all over the "discordlinked:inv" channel.
 * Every player is only read or changed from their own scheduler (Folia-safe).
 */
final class InventoryBridge implements Listener {

    private final DiscordLinkedSpy plugin;
    private final SnapshotOutbox outbox;
    /** Restores arrive in chunks because proxy-to-server messages are limited to about 32 KB. */
    private final Map<String, byte[][]> restoreParts = new ConcurrentHashMap<>();

    InventoryBridge(DiscordLinkedSpy plugin, SnapshotOutbox outbox) {
        this.plugin = plugin;
        this.outbox = outbox;
    }

    // ---- Messages from the proxy ----

    void handle(Player carrier, byte[] message) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            switch (in.readUTF()) {
                case "REQ" -> sendLive(carrier, in.readUTF());
                case "ACK" -> outbox.acknowledge(in.readUTF());
                case "SNAPNOW" -> {
                    String by = in.readUTF();
                    carrier.getScheduler().run(plugin, task -> snapshot(carrier, "MANUAL", by), null);
                }
                case "RESTORE" -> receiveRestorePart(carrier, in);
                default -> { }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Bad inventory message: " + e.getMessage());
        }
    }

    /** The proxy asks through the viewed player's own connection, so the carrier is that player. */
    private void sendLive(Player player, String requestId) {
        player.getScheduler().run(plugin, task -> {
            byte[] json = InventorySnapshot.of(player).toString().getBytes(StandardCharsets.UTF_8);
            send(player, out -> {
                out.writeUTF("INV");
                out.writeUTF(requestId);
                writeBytes(out, json);
            });
        }, null);
    }

    private void receiveRestorePart(Player player, DataInputStream in) throws IOException {
        String requestId = in.readUTF();
        int part = in.readInt();
        int parts = in.readInt();
        boolean includeEnder = in.readBoolean();
        byte[] chunk = readBytes(in);
        if (parts <= 0 || parts > 512 || part < 0 || part >= parts) {
            return;
        }
        byte[][] buffer = restoreParts.computeIfAbsent(requestId, id -> new byte[parts][]);
        if (buffer.length != parts) {
            return;
        }
        buffer[part] = chunk;
        for (byte[] piece : buffer) {
            if (piece == null) {
                return;
            }
        }
        restoreParts.remove(requestId);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (byte[] piece : buffer) {
            whole.write(piece);
        }
        byte[] data = whole.toByteArray();
        player.getScheduler().run(plugin, task -> {
            String result;
            try {
                snapshot(player, "BEFORE_RESTORE", "automatic backup before a restore");
                InventoryCodec.apply(player, data, includeEnder);
                result = "ok";
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Inventory restore for " + player.getName() + " failed: " + e);
                result = "the saved items couldn't be read on this server (" + e.getMessage() + ")";
            }
            String outcome = result;
            send(player, out -> {
                out.writeUTF("RESTORED");
                out.writeUTF(requestId);
                out.writeUTF(outcome);
            });
        }, () -> {
            String outcome = "the player left before the restore";
            plugin.getLogger().info("Restore for " + player.getName() + " skipped: " + outcome);
        });
    }

    // ---- Automatic snapshots ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // Runs before the items are dropped, so this is what they died with.
        String cause = event.deathMessage() == null ? ""
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        snapshot(event.getEntity(), "DEATH", cause);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A moment later, once any restore from a crashed spy session has been applied.
        player.getScheduler().execute(plugin, () -> snapshot(player, "JOIN", ""), null, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        snapshot(event.getPlayer(), "QUIT", "");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        snapshot(event.getPlayer(), "WORLD_CHANGE",
                event.getFrom().getName() + " -> " + event.getPlayer().getWorld().getName());
    }

    /** Takes a snapshot and queues it for the proxy. Call on the player's own thread. */
    void snapshot(Player player, String reason, String detail) {
        String id = UUID.randomUUID().toString();
        byte[] display = InventorySnapshot.of(player).toString().getBytes(StandardCharsets.UTF_8);
        byte[] data = InventoryCodec.save(player);
        int stacks = InventoryCodec.countStacks(player);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("SNAP");
            out.writeUTF(id);
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(player.getName());
            out.writeUTF(reason);
            out.writeUTF(detail.length() > 300 ? detail.substring(0, 300) : detail);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(stacks);
            writeBytes(out, display);
            writeBytes(out, data);
            outbox.add(id, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Deliver soon instead of waiting for the next regular flush.
        Bukkit.getGlobalRegionScheduler().execute(plugin, outbox::flush);
    }

    // ---- Helpers ----

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private void send(Player carrier, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            body.write(out);
            carrier.sendPluginMessage(plugin, DiscordLinkedSpy.INVENTORY_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) {
            throw new IOException("bad length " + length);
        }
        return in.readNBytes(length);
    }
}
