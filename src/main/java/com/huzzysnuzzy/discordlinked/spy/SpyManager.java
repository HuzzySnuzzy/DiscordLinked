package com.huzzysnuzzy.discordlinked.spy;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.TimeFormat;
import com.huzzysnuzzy.discordlinked.storage.PlayerRef;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Website "Spy" sessions: puts an admin's Minecraft character in spectator next to a player, via the
 * DiscordLinkedSpy plugin on the backend server. While a session runs the admin's website is locked.
 */
public final class SpyManager {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("discordlinked", "spy");
    private static final long START_TIMEOUT_SECONDS = 8;

    public enum State { STARTING, ACTIVE }

    public static final class Session {
        public final long adminDiscordId;
        public final String actor;
        public final UUID adminUuid;
        public final PlayerRef target;
        public final String serverName;
        /** Where the admin was before being moved to the target's server; null if already there. */
        final RegisteredServer originalServer;
        public volatile State state = State.STARTING;
        public volatile Instant activeSince;
        public volatile int distance = -1;

        Session(long adminDiscordId, String actor, UUID adminUuid, PlayerRef target, String serverName,
                RegisteredServer originalServer) {
            this.adminDiscordId = adminDiscordId;
            this.actor = actor;
            this.adminUuid = adminUuid;
            this.target = target;
            this.serverName = serverName;
            this.originalServer = originalServer;
        }

        public Duration elapsed() {
            return activeSince == null ? Duration.ZERO : Duration.between(activeSince, Instant.now());
        }
    }

    /** How a session finished, shown once on the admin's website. {@code failed} means it never started. */
    public record Ended(String targetName, UUID targetUuid, String reason, Duration duration, boolean failed) {
    }

    private final DiscordLinked plugin;
    private final Map<Long, Session> byDiscord = new ConcurrentHashMap<>();
    private final Map<UUID, Session> byAdmin = new ConcurrentHashMap<>();
    private final Map<Long, Ended> lastEnded = new ConcurrentHashMap<>();

    public SpyManager(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    public Session current(long adminDiscordId) {
        return byDiscord.get(adminDiscordId);
    }

    /** The most recent ended session for this admin, cleared once read. */
    public Ended takeEnded(long adminDiscordId) {
        return lastEnded.remove(adminDiscordId);
    }

    /**
     * Starts a session. Returns an error message, or null if it is starting; the outcome then shows up
     * through {@link #current} and {@link #takeEnded}.
     */
    public synchronized String start(long adminDiscordId, String actor, Player admin, Player target, PlayerRef targetRef) {
        if (byDiscord.containsKey(adminDiscordId) || byAdmin.containsKey(admin.getUniqueId())) {
            return "You already have a spy session running";
        }
        if (admin.getUniqueId().equals(target.getUniqueId())) {
            return "You can't spy on yourself";
        }
        Optional<ServerConnection> targetServer = target.getCurrentServer();
        Optional<ServerConnection> adminServer = admin.getCurrentServer();
        if (targetServer.isEmpty() || adminServer.isEmpty()) {
            return "You and the player both need to be on a server";
        }
        RegisteredServer destination = targetServer.get().getServer();
        boolean sameServer = adminServer.get().getServer().equals(destination);
        Session session = new Session(adminDiscordId, actor, admin.getUniqueId(), targetRef,
                destination.getServerInfo().getName(), sameServer ? null : adminServer.get().getServer());
        byDiscord.put(adminDiscordId, session);
        byAdmin.put(admin.getUniqueId(), session);

        if (sameServer) {
            sendStart(session, admin);
        } else {
            admin.createConnectionRequest(destination).connect().whenComplete((result, error) -> {
                if (error != null || result == null || !result.isSuccessful()) {
                    fail(session, "couldn't move you to " + session.serverName);
                } else {
                    // Give the backend a moment to finish the join before it gets the START.
                    plugin.proxy().getScheduler().buildTask(plugin, () -> sendStart(session, admin))
                            .delay(1500, TimeUnit.MILLISECONDS).schedule();
                }
            });
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (session.state == State.STARTING && byAdmin.get(session.adminUuid) == session) {
                fail(session, "no reply from " + session.serverName + ". Is the DiscordLinkedSpy plugin installed there?");
            }
        }).delay(START_TIMEOUT_SECONDS + (sameServer ? 0 : 3), TimeUnit.SECONDS).schedule();
        return null;
    }

    private void sendStart(Session session, Player admin) {
        if (byAdmin.get(session.adminUuid) != session) {
            return;
        }
        send(admin, "START", out -> {
            out.writeUTF(session.target.uuid().toString());
            out.writeDouble(plugin.config().spyMaxDistance());
        });
    }

    /** Ends a running session from the proxy side (website button, kick/suspend, someone left). */
    public void end(Session session, String reason) {
        finish(session, reason, true, false);
    }

    private void fail(Session session, String reason) {
        finish(session, reason, true, true);
    }

    private synchronized void finish(Session session, String reason, boolean tellBackend, boolean failed) {
        if (byAdmin.get(session.adminUuid) != session) {
            return; // already ended
        }
        byAdmin.remove(session.adminUuid);
        byDiscord.remove(session.adminDiscordId);
        Duration duration = session.elapsed();
        boolean wasActive = session.state == State.ACTIVE;

        Optional<Player> admin = plugin.proxy().getPlayer(session.adminUuid);
        if (admin.isPresent()) {
            if (tellBackend && isOn(admin.get(), session.serverName)) {
                send(admin.get(), "STOP", out -> { });
            }
            if (session.originalServer != null) {
                RegisteredServer back = session.originalServer;
                plugin.proxy().getScheduler().buildTask(plugin, () ->
                        plugin.proxy().getPlayer(session.adminUuid).ifPresent(p -> p.createConnectionRequest(back).fireAndForget()))
                        .delay(700, TimeUnit.MILLISECONDS).schedule();
            }
            admin.get().sendMessage(plugin.messages().minecraft(failed ? "minecraft.spy.failed" : "minecraft.spy.ended",
                    "player", session.target.name(), "reason", reason, "time", TimeFormat.preciseDuration(duration)));
        }
        if (wasActive) {
            plugin.audit().log(session.target.uuid(), session.target.name(), session.target.discordId(),
                    EventTypes.SPY_END, "after " + TimeFormat.preciseDuration(duration) + ": " + reason, session.actor);
        }
        lastEnded.put(session.adminDiscordId,
                new Ended(session.target.name(), session.target.uuid(), reason, duration, failed || !wasActive));
    }

    // ---- Events ----

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        // Never pass these on: a player's client must not be able to send START to a backend.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        Player admin = connection.getPlayer();
        Session session = byAdmin.get(admin.getUniqueId());
        if (session == null) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String type = in.readUTF();
            String detail = in.readUTF();
            switch (type) {
                case "STARTED" -> {
                    session.state = State.ACTIVE;
                    session.activeSince = Instant.now();
                    admin.sendMessage(plugin.messages().minecraft("minecraft.spy.started",
                            "player", session.target.name(), "distance", String.valueOf(plugin.config().spyMaxDistance())));
                    plugin.audit().log(session.target.uuid(), session.target.name(), session.target.discordId(),
                            EventTypes.SPY_START, "on " + session.serverName, session.actor);
                }
                case "FAILED" -> fail(session, detail);
                case "ENDED" -> finish(session, detail, false, false);
                case "DIST" -> {
                    session.distance = Integer.parseInt(detail);
                    admin.sendActionBar(plugin.messages().minecraft("minecraft.spy.actionbar",
                            "player", session.target.name(), "distance", detail,
                            "time", TimeFormat.preciseDuration(session.elapsed())));
                }
                default -> plugin.logger().warn("Unknown spy message from {}: {}", session.serverName, type);
            }
        } catch (IOException | NumberFormatException e) {
            plugin.logger().warn("Bad spy message from {}", session.serverName, e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Session asAdmin = byAdmin.get(uuid);
        if (asAdmin != null) {
            // The backend restores them on quit, so there's nothing to send.
            finish(asAdmin, "you left the server", false, false);
        }
        for (Session session : byAdmin.values()) {
            if (session.target.uuid().equals(uuid)) {
                end(session, "the player left the server");
            }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String server = event.getServer().getServerInfo().getName();
        Session asAdmin = byAdmin.get(uuid);
        if (asAdmin != null && asAdmin.state == State.ACTIVE && !server.equals(asAdmin.serverName)) {
            finish(asAdmin, "you changed server", false, false);
        }
        for (Session session : byAdmin.values()) {
            if (session.target.uuid().equals(uuid) && !server.equals(session.serverName)) {
                end(session, "the player changed server");
            }
        }
    }

    public void shutdown() {
        for (Session session : byAdmin.values()) {
            end(session, "proxy shutting down");
        }
    }

    // ---- Helpers ----

    private static boolean isOn(Player player, String server) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName().equals(server)).orElse(false);
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private void send(Player admin, String type, Writer body) {
        admin.getCurrentServer().ifPresent(connection -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(type);
                body.write(out);
                connection.sendPluginMessage(CHANNEL, bytes.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
