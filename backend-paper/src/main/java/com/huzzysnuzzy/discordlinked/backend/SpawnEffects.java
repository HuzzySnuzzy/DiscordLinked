package com.huzzysnuzzy.discordlinked.backend;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Slow, swirling particles at the network's spawn while players are near it: purple sparks circling at different
 * heights, enchanting glyphs drawn into the middle and portal motes rising from the ground. The proxy says where the
 * spawn is (SPAWN_FX, its spawn-particles setting). Runs on the region that owns the spot, so it's Folia-safe.
 */
final class SpawnEffects {

    /** Players this close (blocks) see it; with nobody near, nothing is sent. */
    static final double NEAR = 48;
    /** Ticks between frames. */
    static final long PERIOD = 2;

    private final DiscordLinkedBackend plugin;
    private ScheduledTask task;
    private Location at;
    private long frame;

    SpawnEffects(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
    }

    /** From the proxy: on at that spot ({@code world} empty: this server's world spawn), or off. */
    synchronized void set(boolean on, String world, double x, double y, double z) {
        Location where = on ? locate(world, x, y, z) : null;
        if (where == null ? at == null : where.equals(at)) {
            return; // said again when someone joins; nothing changed
        }
        stop();
        if (where == null) {
            return;
        }
        at = where;
        try {
            task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, where, t -> frame(where), 1, PERIOD);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not start the spawn particles: " + e.getMessage());
            at = null;
        }
    }

    synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        at = null;
    }

    /** Where they're drawn now (null: nowhere). */
    synchronized Location at() {
        return at == null ? null : at.clone();
    }

    private static Location locate(String world, double x, double y, double z) {
        if (world.isEmpty()) {
            World first = Bukkit.getWorlds().stream().findFirst().orElse(null);
            if (first == null) {
                return null;
            }
            Location spawn = first.getSpawnLocation();
            return new Location(first, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5);
        }
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    private void frame(Location center) {
        World world = center.getWorld();
        if (world == null || center.getNearbyPlayers(NEAR).isEmpty()) {
            return;
        }
        long t = frame++;
        // Three purple sparks circling slowly, each rising and sinking on its own.
        for (int k = 0; k < 3; k++) {
            double angle = t * 0.12 + k * (Math.PI * 2 / 3);
            double height = 0.3 + 1.1 * (0.5 + 0.5 * Math.sin(t * 0.045 + k * 2.1));
            world.spawnParticle(Particle.WITCH, center.clone().add(Math.cos(angle) * 1.4, height, Math.sin(angle) * 1.4),
                    1, 0, 0, 0, 0);
        }
        // Two motes turning the other way, closer in and higher up.
        for (int k = 0; k < 2; k++) {
            double angle = -t * 0.2 + k * Math.PI;
            world.spawnParticle(Particle.REVERSE_PORTAL,
                    center.clone().add(Math.cos(angle) * 0.6, 1.7 + 0.15 * Math.sin(t * 0.1), Math.sin(angle) * 0.6),
                    1, 0, 0, 0, 0);
        }
        // Enchanting glyphs drawn in from around it into the middle (they fly from spot + offset to the spot).
        if (t % 2 == 0) {
            double angle = t * 0.37;
            world.spawnParticle(Particle.ENCHANT, center.clone().add(0, 1.2, 0), 0,
                    Math.cos(angle) * 2, 0.8 + 0.6 * Math.sin(t * 0.21), Math.sin(angle) * 2, 1);
        }
        // Now and then, portal dust from the ground.
        if (t % 10 == 0) {
            world.spawnParticle(Particle.PORTAL, center.clone().add(0, 0.1, 0), 10, 0.7, 0.05, 0.7, 0.3);
        }
    }
}
