package com.huzzysnuzzy.discordlinked.spy;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.TimeFormat;
import com.huzzysnuzzy.discordlinked.storage.InventorySnapshotRecord;
import com.huzzysnuzzy.discordlinked.storage.PendingRestore;
import com.velocitypowered.api.event.Subscribe;
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
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the DiscordLinkedSpy plugin on the backend servers about inventories: live views, snapshots
 * (sent by the servers on death/join/quit/world change, stored here so the website can show them even
 * when that server is empty) and restores. Only messages that arrive from a backend connection are
 * accepted; players' clients can't send on this channel.
 */
public final class InventoryRequests {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("discordlinked", "inv");
    private static final long TIMEOUT_SECONDS = 5;
    private static final int MAX_BLOB_BYTES = 1 << 20;
    /** Proxy-to-server plugin messages are limited to 32767 bytes, so restores go in chunks. */
    private static final int RESTORE_CHUNK_BYTES = 30_000;

    private record Pending(UUID player, CompletableFuture<String> result) {
    }

    private final DiscordLinked plugin;
    private final Map<String, Pending> liveRequests = new ConcurrentHashMap<>();
    private final Map<String, Pending> restores = new ConcurrentHashMap<>();

    public InventoryRequests(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    // ---- Requests to a player's server ----

    /** Completes with the live inventory JSON, or exceptionally if the server doesn't answer in time. */
    public CompletableFuture<String> request(Player player) {
        return ask(player, liveRequests, "REQ", (id, out) -> out.writeUTF(id));
    }

    /** Asks the player's server to save a snapshot now; it then arrives like any other snapshot. */
    public boolean snapshotNow(Player player, String actor) {
        return send(player, out -> {
            out.writeUTF("SNAPNOW");
            out.writeUTF(actor.length() > 200 ? actor.substring(0, 200) : actor);
        });
    }

    /**
     * Replaces the online player's items with a snapshot. Completes with "ok" or a reason it failed.
     * The backend saves a "before restore" snapshot first.
     */
    public CompletableFuture<String> restore(Player player, InventorySnapshotRecord snapshot, boolean includeEnder) {
        byte[] data = snapshot.data();
        int parts = Math.max(1, (data.length + RESTORE_CHUNK_BYTES - 1) / RESTORE_CHUNK_BYTES);
        return sendRestore(player, data, parts, includeEnder);
    }

    private CompletableFuture<String> sendRestore(Player player, byte[] data, int parts, boolean includeEnder) {
        CompletableFuture<String> result = new CompletableFuture<>();
        String id = UUID.randomUUID().toString();
        restores.put(id, new Pending(player.getUniqueId(), result));
        result.orTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS).whenComplete((r, e) -> restores.remove(id));
        for (int part = 0; part < parts; part++) {
            int from = part * RESTORE_CHUNK_BYTES;
            int to = Math.min(data.length, from + RESTORE_CHUNK_BYTES);
            int index = part;
            boolean sent = send(player, out -> {
                out.writeUTF("RESTORE");
                out.writeUTF(id);
                out.writeInt(index);
                out.writeInt(parts);
                out.writeBoolean(includeEnder);
                out.writeInt(to - from);
                out.write(data, from, to - from);
            });
            if (!sent) {
                result.completeExceptionally(new IllegalStateException("the player isn't on a server"));
                break;
            }
        }
        return result;
    }

    private interface IdBody {
        void write(String id, DataOutputStream out) throws IOException;
    }

    private CompletableFuture<String> ask(Player player, Map<String, Pending> pending, String type, IdBody body) {
        CompletableFuture<String> result = new CompletableFuture<>();
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(player.getUniqueId(), result));
        result.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((json, error) -> pending.remove(id));
        if (!send(player, out -> {
            out.writeUTF(type);
            body.write(id, out);
        })) {
            result.completeExceptionally(new IllegalStateException("the player isn't on a server"));
        }
        return result;
    }

    // ---- Messages from the servers ----

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
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            switch (in.readUTF()) {
                case "INV" -> {
                    Pending request = liveRequests.get(in.readUTF());
                    byte[] json = readBlob(in);
                    if (request != null && connection.getPlayer().getUniqueId().equals(request.player())) {
                        request.result().complete(new String(json, StandardCharsets.UTF_8));
                    }
                }
                case "RESTORED" -> {
                    Pending request = restores.get(in.readUTF());
                    String outcome = in.readUTF();
                    if (request != null && connection.getPlayer().getUniqueId().equals(request.player())) {
                        request.result().complete(outcome);
                    }
                }
                case "SNAP" -> receiveSnapshot(connection, in);
                default -> { }
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.logger().warn("Bad inventory message from {}: {}", connection.getServerInfo().getName(), e.getMessage());
        }
    }

    private void receiveSnapshot(ServerConnection connection, DataInputStream in) throws IOException {
        String id = in.readUTF();
        UUID uuid = UUID.fromString(in.readUTF());
        String name = in.readUTF();
        String reason = in.readUTF();
        String detail = in.readUTF();
        Instant createdAt = Instant.ofEpochMilli(in.readLong());
        int stacks = in.readInt();
        String display = new String(readBlob(in), StandardCharsets.UTF_8);
        byte[] data = readBlob(in);
        String server = connection.getServerInfo().getName();
        // Store off the network thread, then confirm so the server can delete its copy.
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                plugin.database().addSnapshot(uuid, name, server, reason, detail, createdAt, stacks, display, data);
                plugin.database().pruneSnapshots(uuid, plugin.config().snapshotsPerPlayer());
            } catch (SQLException e) {
                plugin.logger().error("Could not store an inventory snapshot for {}", name, e);
                return;
            }
            send(connection, out -> {
                out.writeUTF("ACK");
                out.writeUTF(id);
            });
            if (reason.equals("DEATH")) {
                Long discordId = discordId(uuid);
                plugin.audit().log(uuid, name, discordId, EventTypes.DEATH, detail.isEmpty() ? "died" : detail);
            }
            plugin.audit().live().playerChanged("snapshot", uuid);
        }).schedule();
    }

    /** Applies a restore that was queued while the player was offline. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        plugin.proxy().getScheduler().buildTask(plugin, () -> applyPending(player)).delay(3, TimeUnit.SECONDS).schedule();
    }

    private void applyPending(Player player) {
        try {
            Optional<PendingRestore> pending = plugin.database().pendingRestore(player.getUniqueId());
            if (pending.isEmpty() || player.getCurrentServer().isEmpty()) {
                return;
            }
            Optional<InventorySnapshotRecord> snapshot = plugin.database().snapshot(pending.get().snapshotId());
            plugin.database().clearPendingRestores(player.getUniqueId());
            if (snapshot.isEmpty()) {
                return;
            }
            PendingRestore p = pending.get();
            restore(player, snapshot.get(), p.includeEnderChest()).whenComplete((outcome, error) -> {
                String result = error != null ? "no reply from the server (is DiscordLinkedSpy installed there?)"
                        : outcome.equals("ok") ? "applied when they joined" : "failed: " + outcome;
                plugin.audit().log(player.getUniqueId(), player.getUsername(), discordId(player.getUniqueId()),
                        EventTypes.INVENTORY_RESTORE, describe(snapshot.get()) + ", " + result + ": " + p.reason(), p.actor());
            });
        } catch (SQLException e) {
            plugin.logger().error("Could not check queued inventory restores for {}", player.getUsername(), e);
        }
    }

    public static String describe(InventorySnapshotRecord snapshot) {
        return "snapshot #" + snapshot.id() + " (" + snapshot.reason().toLowerCase().replace('_', ' ') + ", "
                + TimeFormat.dateTime(snapshot.createdAt()) + ")";
    }

    // ---- Helpers ----

    private Long discordId(UUID uuid) {
        try {
            return plugin.database().findByUuid(uuid).map(a -> a.discordId()).orElse(null);
        } catch (SQLException e) {
            return null;
        }
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private boolean send(Player player, Body body) {
        Optional<ServerConnection> connection = player.getCurrentServer();
        return connection.isPresent() && send(connection.get(), body);
    }

    private static boolean send(ServerConnection connection, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            body.write(out);
            return connection.sendPluginMessage(CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BLOB_BYTES) {
            throw new IOException("bad length " + length);
        }
        return in.readNBytes(length);
    }
}
