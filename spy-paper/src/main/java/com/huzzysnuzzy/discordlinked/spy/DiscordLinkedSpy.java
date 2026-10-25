package com.huzzysnuzzy.discordlinked.spy;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Carries out spy sessions started from the DiscordLinked website. The Velocity proxy sends START/STOP
 * over the plugin message channel (it drops any copy sent by a player's client, so only the proxy can
 * reach this). The admin's original location, game mode and flight are stored on the player itself, so
 * they're restored even after a crash.
 */
public final class DiscordLinkedSpy extends JavaPlugin implements PluginMessageListener, Listener {

    static final String CHANNEL = "discordlinked:spy";
    private static final int DISTANCE_REPORT_SECONDS = 2;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private NamespacedKey stateKey;

    private record Session(UUID admin, UUID target, double maxDistance) {
    }

    @Override
    public void onEnable() {
        stateKey = new NamespacedKey(this, "spy_restore");
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Session session : Map.copyOf(sessions).values()) {
            Player admin = Bukkit.getPlayer(session.admin());
            if (admin != null) {
                end(admin, session, "server stopping");
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String type = in.readUTF();
            // The proxy always sends through the admin's own connection, so the carrier is the admin.
            Player admin = carrier;
            switch (type) {
                case "START" -> start(admin, UUID.fromString(in.readUTF()), in.readDouble());
                case "STOP" -> {
                    Session session = sessions.get(admin.getUniqueId());
                    if (session != null) {
                        end(admin, session, null);
                    }
                }
                default -> getLogger().warning("Unknown spy message: " + type);
            }
        } catch (IOException | IllegalArgumentException e) {
            getLogger().warning("Bad spy message: " + e.getMessage());
        }
    }

    private void start(Player admin, UUID targetId, double maxDistance) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            send(admin, "FAILED", "that player isn't on this server");
            return;
        }
        if (target.equals(admin)) {
            send(admin, "FAILED", "you can't spy on yourself");
            return;
        }
        Session existing = sessions.get(admin.getUniqueId());
        if (existing != null) {
            end(admin, existing, null);
        }
        saveState(admin);
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target.getLocation());
        target.hidePlayer(this, admin);
        sessions.put(admin.getUniqueId(), new Session(admin.getUniqueId(), targetId, maxDistance));
        send(admin, "STARTED", "");
    }

    private void tick() {
        long second = System.currentTimeMillis() / 1000;
        for (Session session : Map.copyOf(sessions).values()) {
            Player admin = Bukkit.getPlayer(session.admin());
            if (admin == null) {
                sessions.remove(session.admin());
                continue;
            }
            Player target = Bukkit.getPlayer(session.target());
            if (target == null) {
                end(admin, session, "the player left the server");
                continue;
            }
            if (!admin.getWorld().equals(target.getWorld())) {
                end(admin, session, "out of range (different world)");
                continue;
            }
            double distance = admin.getLocation().distance(target.getLocation());
            if (distance > session.maxDistance()) {
                end(admin, session, "out of range (" + Math.round(distance) + " blocks)");
            } else if (second % DISTANCE_REPORT_SECONDS == 0) {
                send(admin, "DIST", String.valueOf(Math.round(distance)));
            }
        }
    }

    /** Restores the admin and, when {@code reason} isn't null, tells the proxy the session ended here. */
    private void end(Player admin, Session session, String reason) {
        sessions.remove(session.admin());
        Player target = Bukkit.getPlayer(session.target());
        if (target != null) {
            target.showPlayer(this, admin);
        }
        restoreState(admin);
        if (reason != null) {
            send(admin, "ENDED", reason);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Leftover state means the server stopped or crashed mid-session.
        restoreState(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            Player target = Bukkit.getPlayer(session.target());
            if (target != null) {
                target.showPlayer(this, player);
            }
            restoreState(player);
        }
    }

    // ---- Saving and restoring the admin ----

    private void saveState(Player player) {
        Location loc = player.getLocation();
        String state = String.join(";",
                loc.getWorld().getName(),
                Double.toString(loc.getX()), Double.toString(loc.getY()), Double.toString(loc.getZ()),
                Float.toString(loc.getYaw()), Float.toString(loc.getPitch()),
                player.getGameMode().name(),
                Boolean.toString(player.getAllowFlight()), Boolean.toString(player.isFlying()));
        player.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private void restoreState(Player player) {
        String state = player.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
        if (state == null) {
            return;
        }
        player.getPersistentDataContainer().remove(stateKey);
        String[] parts = state.split(";");
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                world = Bukkit.getWorlds().get(0);
            }
            player.setGameMode(GameMode.valueOf(parts[6]));
            player.teleport(new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5])));
            player.setAllowFlight(Boolean.parseBoolean(parts[7]));
            player.setFlying(player.getAllowFlight() && Boolean.parseBoolean(parts[8]));
        } catch (RuntimeException e) {
            getLogger().warning("Could not fully restore " + player.getName() + " after spying: " + e.getMessage());
            player.setGameMode(getServer().getDefaultGameMode());
        }
    }

    private void send(Player carrier, String type, String detail) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(type);
            out.writeUTF(detail);
            carrier.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
