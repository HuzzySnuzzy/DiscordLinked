package com.huzzysnuzzy.discordlinked.backend;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * Website teleports: the admin goes to a player's location (where they logout, if they're offline) or to their bed
 * or respawn anchor. Logout spots are saved here as players leave; for players who left before that, the server's
 * own saved player data is used.
 */
final class Teleports {

    private final DiscordLinkedBackend plugin;
    private final SpyService spy;
    private final Path file;
    private final Properties spots = new Properties();

    Teleports(DiscordLinkedBackend plugin, SpyService spy) {
        this.plugin = plugin;
        this.spy = spy;
        this.file = plugin.getDataFolder().toPath().resolve("logout-spots.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                spots.load(in);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read saved logout spots: " + e.getMessage());
            }
        }
    }

    /** Call on the leaving player's thread. */
    void saveLogoutSpot(Player player) {
        Location l = player.getLocation();
        synchronized (spots) {
            spots.setProperty(player.getUniqueId().toString(), l.getWorld().getName() + "," + l.getX() + "," + l.getY()
                    + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> store());
    }

    private void store() {
        synchronized (spots) {
            try {
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    spots.store(out, "Where players last logged out, for DiscordLinked's website teleports");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save logout spots: " + e.getMessage());
            }
        }
    }

    private Location savedSpot(UUID uuid) {
        String value;
        synchronized (spots) {
            value = spots.getProperty(uuid.toString());
        }
        if (value == null) {
            return null;
        }
        String[] p = value.split(",");
        World world = p.length == 6 ? Bukkit.getWorld(p[0]) : null;
        if (world == null) {
            return null;
        }
        return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                Float.parseFloat(p[4]), Float.parseFloat(p[5]));
    }

    /** {@code mode} "player" or "bed". Called from the message thread; replies "TPRESULT" through the admin. */
    @SuppressWarnings("deprecation") // getBedSpawnLocation: same as getRespawnLocation (beds and anchors)
    void teleport(Player admin, String id, String mode, UUID targetId) {
        boolean bed = "bed".equals(mode);
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) {
            // Read it on the target's own thread (Folia), then move the admin on theirs.
            var task = target.getScheduler().run(plugin, t -> {
                Location to = bed ? target.getBedSpawnLocation() : target.getLocation();
                admin.getScheduler().run(plugin, a -> go(admin, id, to, bed, true), null);
            }, () -> admin.getScheduler().run(plugin, a -> reply(admin, id, "they left the server"), null));
            if (task == null) {
                admin.getScheduler().run(plugin, a -> reply(admin, id, "they left the server"), null);
            }
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetId);
            Location to;
            if (bed) {
                to = offline.getBedSpawnLocation();
            } else {
                to = savedSpot(targetId);
                if (to == null && offline.hasPlayedBefore()) {
                    to = offline.getLocation();
                }
            }
            Location destination = to;
            admin.getScheduler().run(plugin, a -> go(admin, id, destination, bed, false), null);
        });
    }

    private void go(Player admin, String id, Location to, boolean bed, boolean online) {
        if (to == null || to.getWorld() == null) {
            reply(admin, id, bed ? "they have no bed or respawn anchor set on this server"
                    : online ? "their location couldn't be read" : "this server has no saved logout spot for them");
            return;
        }
        if (spy.inSpySession(admin)) {
            reply(admin, id, "end your spy session first");
            return;
        }
        admin.teleportAsync(to).thenAccept(ok -> admin.getScheduler().run(plugin,
                t -> reply(admin, id, Boolean.TRUE.equals(ok) ? "ok" : "the teleport didn't go through"), null));
    }

    private void reply(Player admin, String id, String outcome) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("TPRESULT");
            out.writeUTF(id);
            out.writeUTF(outcome);
            admin.sendPluginMessage(plugin, SpyService.CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
