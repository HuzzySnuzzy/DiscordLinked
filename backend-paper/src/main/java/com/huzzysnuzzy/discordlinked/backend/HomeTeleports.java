package com.huzzysnuzzy.discordlinked.backend;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's part of homes (decided on the proxy, see the proxy's Homes): the wait before /home teleports
 * (moving or getting hurt stops it), the teleport itself, and each player's home names for tab completion.
 * While waiting, portal particles swirl around the player like an enderman's; on arrival there's an enderman's
 * teleport sound (unless the proxy's teleport-effects is off).
 */
final class HomeTeleports implements Listener {

    /** How far a player may drift (blocks) while waiting before it counts as moving. */
    private static final double MOVE_LIMIT = 0.6;

    /** The enderman's teleport sound, by key so it works on any version. */
    static final String WOOSH = "entity.enderman.teleport";

    /** A /home the player is waiting for. Texts are what the proxy sent; {@code effects}: particles and sound. */
    record Request(String id, int warmup, boolean here, String world, double x, double y, double z, float yaw,
                   float pitch, Component warmupText, Component arrivedText, Component movedText, Component hurtText,
                   boolean effects) {
    }

    private record Waiting(Request request, Location start, ScheduledTask task) {
    }

    private final DiscordLinkedBackend plugin;
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> names = new ConcurrentHashMap<>();

    HomeTeleports(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
    }

    static Component text(String json) {
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException e) {
            return Component.text(json);
        }
    }

    // ---- Tab completion ----

    void names(UUID player, List<String> homeNames) {
        names.put(player, List.copyOf(homeNames));
    }

    List<String> complete(Player player, String typed) {
        String start = typed.toLowerCase(Locale.ROOT);
        return names.getOrDefault(player.getUniqueId(), List.of()).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }

    /** Their own home names only (/delhome, /sethome). */
    List<String> completeOwn(Player player, String typed) {
        return complete(player, typed).stream().filter(name -> name.indexOf(':') < 0).toList();
    }

    // ---- /home ----

    /** From the proxy: wait (if asked), then teleport, or say "ready" so the proxy moves them to another server. */
    void start(Player player, Request request) {
        player.getScheduler().run(plugin, task -> {
            cancel(player, null); // a new /home replaces one they were waiting for
            if (request.warmup() <= 0) {
                finish(player, request);
                return;
            }
            player.sendMessage(request.warmupText());
            Location start = player.getLocation();
            long endsAt = System.currentTimeMillis() + request.warmup() * 1000L;
            ScheduledTask check = player.getScheduler().runAtFixedRate(plugin, t -> {
                Waiting current = waiting.get(player.getUniqueId());
                if (current == null || current.request() != request) {
                    t.cancel();
                    return;
                }
                Location now = player.getLocation();
                if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > MOVE_LIMIT * MOVE_LIMIT) {
                    cancel(player, request.movedText());
                    return;
                }
                if (System.currentTimeMillis() >= endsAt) {
                    waiting.remove(player.getUniqueId());
                    t.cancel();
                    finish(player, request);
                    return;
                }
                if (request.effects()) {
                    // More and more of them as the wait runs out.
                    double done = 1 - (endsAt - System.currentTimeMillis()) / (request.warmup() * 1000.0);
                    channel(player, 6 + (int) Math.round(Math.max(0, Math.min(1, done)) * 18));
                }
            }, null, 5, 5);
            if (check != null) {
                waiting.put(player.getUniqueId(), new Waiting(request, start, check));
            }
        }, null);
    }

    private void finish(Player player, Request request) {
        if (request.effects()) {
            burst(player); // where they leave from, for whoever stands there
        }
        if (!request.here()) {
            plugin.link().homeDone(player, request.id(), "ready");
            return;
        }
        // No world: this server's own spawn (/spawn while staff haven't set one).
        World world = request.world().isEmpty() ? Bukkit.getWorlds().stream().findFirst().orElse(null) : Bukkit.getWorld(request.world());
        if (world == null) {
            plugin.link().homeDone(player, request.id(), "no-world");
            return;
        }
        Location to = request.world().isEmpty() ? world.getSpawnLocation()
                : new Location(world, request.x(), request.y(), request.z(), request.yaw(), request.pitch());
        player.teleportAsync(to).whenComplete((ok, error) -> player.getScheduler().run(plugin, t -> {
            boolean arrived = error == null && Boolean.TRUE.equals(ok);
            if (arrived) {
                if (request.effects()) {
                    Location at = player.getLocation();
                    at.getWorld().playSound(at, WOOSH, SoundCategory.PLAYERS, 1f, 1f);
                    burst(player);
                }
                player.sendMessage(request.arrivedText());
            }
            plugin.link().homeDone(player, request.id(), arrived ? "ok" : "failed");
        }, null));
    }

    /** Portal particles around a player who's waiting, like an enderman's. */
    static void channel(Player player, int count) {
        Location at = player.getLocation().add(0, player.getHeight() / 2, 0);
        at.getWorld().spawnParticle(Particle.PORTAL, at, count, 0.3, player.getHeight() / 3, 0.3, 0.6);
    }

    /** A puff of portal particles where a player leaves or arrives. */
    static void burst(Player player) {
        Location at = player.getLocation().add(0, player.getHeight() / 2, 0);
        at.getWorld().spawnParticle(Particle.PORTAL, at, 48, 0.3, player.getHeight() / 3, 0.3, 1.2);
        at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 12, 0.3, player.getHeight() / 3, 0.3, 0.04);
    }

    /** Stops a wait; {@code why} is told to the player (null: quietly, e.g. a newer /home replaced it). */
    private void cancel(Player player, Component why) {
        Waiting old = waiting.remove(player.getUniqueId());
        if (old == null) {
            return;
        }
        old.task().cancel();
        if (why != null) {
            player.sendMessage(why);
        }
        plugin.link().homeDone(player, old.request().id(), "cancelled");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            Waiting current = waiting.get(player.getUniqueId());
            if (current != null) {
                cancel(player, current.request().hurtText());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Waiting old = waiting.remove(uuid);
        if (old != null) {
            old.task().cancel();
        }
        names.remove(uuid);
    }
}
