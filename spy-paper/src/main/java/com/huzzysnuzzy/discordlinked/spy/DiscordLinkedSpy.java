package com.huzzysnuzzy.discordlinked.spy;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries out spy sessions started from the DiscordLinked website. The Velocity proxy sends START/STOP
 * over the plugin message channel (it drops any copy sent by a player's client, so only the proxy can
 * reach this). The admin's original location, game mode and flight are stored on the player itself, so
 * they're restored even after a crash.
 *
 * <p>Works on Paper and Folia. Every player is only touched from their own entity scheduler, which on
 * Folia is the thread that owns their region and on Paper is the main thread. The two players may be in
 * different regions, so the target's position is copied into the session by the target's own task and
 * the admin's task only ever reads that copy.
 */
public final class DiscordLinkedSpy extends JavaPlugin implements PluginMessageListener, Listener {

    static final String CHANNEL = "discordlinked:spy";
    static final String INVENTORY_CHANNEL = "discordlinked:inv";
    private static final long CHECK_PERIOD_TICKS = 20;
    private static final long TARGET_SNAPSHOT_TICKS = 10;
    private static final int DISTANCE_REPORT_SECONDS = 2;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private NamespacedKey stateKey;
    private InventoryBridge inventory;
    private boolean folia;

    /** Where the target was last seen, copied by the target's own scheduler. */
    private record Position(String world, double x, double y, double z) {
        double distance(Location other) {
            double dx = x - other.getX();
            double dy = y - other.getY();
            double dz = z - other.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static final class Session {
        final UUID admin;
        final UUID target;
        final double maxDistance;
        volatile Position targetPosition;
        volatile boolean targetGone;
        volatile ScheduledTask adminTask;
        volatile ScheduledTask targetTask;
        int seconds;

        Session(UUID admin, UUID target, double maxDistance, Position start) {
            this.admin = admin;
            this.target = target;
            this.maxDistance = maxDistance;
            this.targetPosition = start;
        }

        void cancelTasks() {
            if (adminTask != null) {
                adminTask.cancel();
            }
            if (targetTask != null) {
                targetTask.cancel();
            }
        }
    }

    @Override
    public void onEnable() {
        stateKey = new NamespacedKey(this, "spy_restore");
        folia = isFolia();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, INVENTORY_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, INVENTORY_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        try {
            SnapshotOutbox outbox = new SnapshotOutbox(this, getDataFolder().toPath().resolve("outbox"), getLogger());
            inventory = new InventoryBridge(this, outbox);
            getServer().getPluginManager().registerEvents(inventory, this);
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> outbox.flush(), 100L, 100L);
        } catch (java.io.IOException e) {
            getLogger().severe("Inventory snapshots are disabled: " + e.getMessage());
        }
        getLogger().info("Ready for DiscordLinked spy sessions" + (folia ? " (Folia)" : ""));
    }

    @Override
    public void onDisable() {
        for (Session session : sessions.values()) {
            session.cancelTasks();
            Player admin = Bukkit.getPlayer(session.admin);
            // On Paper this runs on the main thread, so restore now. On Folia no scheduler runs during
            // shutdown; the saved state is restored the next time the admin joins.
            if (admin != null && !folia) {
                restoreState(admin);
            }
        }
        sessions.clear();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (INVENTORY_CHANNEL.equals(channel) && inventory != null) {
            inventory.handle(carrier, message);
            return;
        }
        if (!CHANNEL.equals(channel)) {
            return;
        }
        String type;
        UUID targetId = null;
        double maxDistance = 0;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            type = in.readUTF();
            if (type.equals("START")) {
                targetId = UUID.fromString(in.readUTF());
                maxDistance = in.readDouble();
            }
        } catch (IOException | IllegalArgumentException e) {
            getLogger().warning("Bad spy message: " + e.getMessage());
            return;
        }
        // The proxy always sends through the admin's own connection, so the carrier is the admin.
        Player admin = carrier;
        UUID target = targetId;
        double max = maxDistance;
        switch (type) {
            case "START" -> start(admin, target, max);
            case "STOP" -> admin.getScheduler().run(this, task -> {
                Session session = sessions.get(admin.getUniqueId());
                if (session != null) {
                    end(admin, session, null);
                }
            }, null);
            default -> getLogger().warning("Unknown spy message: " + type);
        }
    }

    /** Called from whatever thread delivered the message; hops onto the right player threads. */
    private void start(Player admin, UUID targetId, double maxDistance) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            admin.getScheduler().run(this, task -> send(admin, "FAILED", "that player isn't on this server"), null);
            return;
        }
        if (target.getUniqueId().equals(admin.getUniqueId())) {
            admin.getScheduler().run(this, task -> send(admin, "FAILED", "you can't spy on yourself"), null);
            return;
        }
        // Step 1, on the target's thread: read where they are and hide the admin from them.
        ScheduledTask scheduled = target.getScheduler().run(this, task -> {
            Location location = target.getLocation();
            Position start = position(location);
            target.hidePlayer(this, admin);
            // Step 2, on the admin's thread: save their state, switch to spectator, teleport.
            admin.getScheduler().run(this, adminTask -> begin(admin, target, maxDistance, start, location),
                    () -> target.getScheduler().run(this, t -> target.showPlayer(this, admin), null));
        }, () -> admin.getScheduler().run(this, task -> send(admin, "FAILED", "that player left the server"), null));
        if (scheduled == null) {
            admin.getScheduler().run(this, task -> send(admin, "FAILED", "that player left the server"), null);
        }
    }

    private void begin(Player admin, Player target, double maxDistance, Position start, Location destination) {
        Session previous = sessions.get(admin.getUniqueId());
        if (previous != null) {
            end(admin, previous, null);
        }
        saveState(admin);
        admin.setGameMode(GameMode.SPECTATOR);
        Session session = new Session(admin.getUniqueId(), target.getUniqueId(), maxDistance, start);
        sessions.put(admin.getUniqueId(), session);

        admin.teleportAsync(destination).thenAccept(ok -> admin.getScheduler().run(this, task -> {
            if (sessions.get(admin.getUniqueId()) != session) {
                return;
            }
            if (!Boolean.TRUE.equals(ok)) {
                end(admin, session, null);
                send(admin, "FAILED", "the teleport didn't go through");
                return;
            }
            // The target keeps the session's copy of their position fresh from their own thread.
            session.targetTask = target.getScheduler().runAtFixedRate(this,
                    t -> session.targetPosition = position(target.getLocation()),
                    () -> session.targetGone = true, 1, TARGET_SNAPSHOT_TICKS);
            if (session.targetTask == null) {
                session.targetGone = true;
            }
            session.adminTask = admin.getScheduler().runAtFixedRate(this, t -> check(admin, session),
                    null, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
            send(admin, "STARTED", "");
        }, null));
    }

    /** Runs every second on the admin's thread. */
    private void check(Player admin, Session session) {
        if (sessions.get(admin.getUniqueId()) != session) {
            return;
        }
        if (session.targetGone || Bukkit.getPlayer(session.target) == null) {
            end(admin, session, "the player left the server");
            return;
        }
        Position target = session.targetPosition;
        Location here = admin.getLocation();
        if (!here.getWorld().getName().equals(target.world())) {
            end(admin, session, "out of range (different world)");
            return;
        }
        double distance = target.distance(here);
        if (distance > session.maxDistance) {
            end(admin, session, "out of range (" + Math.round(distance) + " blocks)");
        } else if (++session.seconds % DISTANCE_REPORT_SECONDS == 0) {
            send(admin, "DIST", String.valueOf(Math.round(distance)));
        }
    }

    /**
     * Restores the admin (call on the admin's thread) and, when {@code reason} isn't null, tells the
     * proxy the session ended here.
     */
    private void end(Player admin, Session session, String reason) {
        sessions.remove(session.admin, session);
        session.cancelTasks();
        showAgain(admin, session.target);
        restoreState(admin);
        if (reason != null) {
            send(admin, "ENDED", reason);
        }
    }

    private void showAgain(Player admin, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            target.getScheduler().run(this, task -> target.showPlayer(this, admin), null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Leftover state means the server stopped, crashed or the admin quit mid-session.
        restoreState(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.cancelTasks();
            showAgain(player, session.target);
            // Teleporting a leaving player isn't reliable, so put the game mode back now and leave the
            // saved state for the next join to finish the restore.
            String state = player.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
            if (state != null) {
                try {
                    player.setGameMode(GameMode.valueOf(state.split(";")[6]));
                } catch (RuntimeException ignored) {
                    player.setGameMode(getServer().getDefaultGameMode());
                }
            }
        }
    }

    // ---- Saving and restoring the admin (always on the admin's own thread) ----

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
            GameMode gameMode = GameMode.valueOf(parts[6]);
            boolean allowFlight = Boolean.parseBoolean(parts[7]);
            boolean flying = allowFlight && Boolean.parseBoolean(parts[8]);
            player.setGameMode(gameMode);
            Location back = new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
            player.teleportAsync(back).thenAccept(ok -> player.getScheduler().run(this, task -> {
                player.setAllowFlight(allowFlight);
                player.setFlying(flying);
            }, null));
        } catch (RuntimeException e) {
            getLogger().warning("Could not fully restore " + player.getName() + " after spying: " + e.getMessage());
            player.setGameMode(getServer().getDefaultGameMode());
        }
    }

    // ---- Helpers ----

    private static Position position(Location location) {
        return new Position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
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

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
