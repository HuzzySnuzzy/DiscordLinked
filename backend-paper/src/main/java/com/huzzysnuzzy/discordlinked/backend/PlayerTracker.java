package com.huzzysnuzzy.discordlinked.backend;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tells the proxy when players go AFK or come back (AFK time earns no credits) and when they make an
 * advancement. Looking around counts as activity but being pushed by water or pistons doesn't, so AFK pools
 * don't keep anyone "active".
 */
final class PlayerTracker implements Listener {

    private static final long CHECK_SECONDS = 15;

    private final DiscordLinkedBackend plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    PlayerTracker(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
    }

    void start() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> checkAfk(), CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        afk.remove(player.getUniqueId());
        if (!plugin.link().synced()) {
            // A second later, once the proxy has finished setting up the connection.
            player.getScheduler().runDelayed(plugin, task -> {
                if (!plugin.link().synced()) {
                    plugin.link().hello(player);
                }
            }, null, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastActive.remove(uuid);
        afk.remove(uuid);
        plugin.forgetCooldown(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            active(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.settings().advancementCredits) {
            return;
        }
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        // Recipes and hidden helper advancements have no display or aren't announced; only real ones count.
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        plugin.link().advancement(event.getPlayer(), event.getAdvancement().getKey().toString(), title,
                display.frame().name());
    }

    private void active(Player player) {
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
            plugin.link().afk(player, false);
        }
    }

    private void checkAfk() {
        int minutes = plugin.settings().afkMinutes;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (minutes <= 0) {
                if (afk.remove(uuid)) {
                    plugin.link().afk(player, false);
                }
                continue;
            }
            long last = lastActive.computeIfAbsent(uuid, id -> now);
            if (now - last >= minutes * 60_000L && afk.add(uuid)) {
                plugin.link().afk(player, true);
            }
        }
    }
}
