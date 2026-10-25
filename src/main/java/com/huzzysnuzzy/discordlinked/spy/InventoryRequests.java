package com.huzzysnuzzy.discordlinked.spy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Asks a player's backend server (through the DiscordLinkedSpy plugin) for a snapshot of their
 * inventory, as JSON. The request travels over the player's own connection, and only an answer that
 * comes back over that same connection is accepted.
 */
public final class InventoryRequests {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("discordlinked", "inv");
    private static final long TIMEOUT_SECONDS = 5;
    private static final int MAX_JSON_BYTES = 1 << 20;

    private record Pending(UUID player, CompletableFuture<String> result) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Completes with the inventory JSON, or exceptionally if the server doesn't answer in time. */
    public CompletableFuture<String> request(Player player) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null) {
            result.completeExceptionally(new IllegalStateException("the player isn't on a server"));
            return result;
        }
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(player.getUniqueId(), result));
        result.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((json, error) -> pending.remove(id));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("REQ");
            out.writeUTF(id);
            connection.sendPluginMessage(CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            result.completeExceptionally(e);
        }
        return result;
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
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            if (!in.readUTF().equals("INV")) {
                return;
            }
            Pending request = pending.get(in.readUTF());
            int length = in.readInt();
            if (request == null || length < 0 || length > MAX_JSON_BYTES
                    || !connection.getPlayer().getUniqueId().equals(request.player())) {
                return;
            }
            request.result().complete(new String(in.readNBytes(length), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // malformed; the request times out
        }
    }
}
