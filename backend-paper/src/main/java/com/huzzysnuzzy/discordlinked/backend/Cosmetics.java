package com.huzzysnuzzy.discordlinked.backend;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetics players bought on the website, as the proxy says they use them: a chat prefix (through LuckPerms when
 * it's installed, else in front of their name in chat and the tab list), a particle trail while they walk, a
 * particle aura while they stand still (both in the shape, animation and colour they picked), and KeepInventory, which keeps their items and XP when they die but stops them from hurting other players.
 *
 * <p>Works on Folia: everything about a player runs on that player's own scheduler or in their events.
 */
final class Cosmetics implements Listener {

    /**
     * What a player uses. Trail and aura are particle names ("" = none) with their look ("shape;animation;colour").
     * {@code keepInventoryFrom}: epoch millis it works from, 0 = off.
     */
    record State(String prefix, String trail, String trailStyle, String aura, String auraStyle, long keepInventoryFrom) {
        static final State NONE = new State("", "", "", "", "", 0);

        boolean sameParticles(State other) {
            return trail.equals(other.trail) && trailStyle.equals(other.trailStyle) && aura.equals(other.aura)
                    && auraStyle.equals(other.auraStyle);
        }
    }

    /** One particle effect, ready to draw. */
    private record Effect(Particle particle, ParticleShapes.Style style) {
    }

    private static final ParticleShapes.Style TRAIL_STYLE = new ParticleShapes.Style("cloud", "still", "");
    private static final ParticleShapes.Style AURA_STYLE = new ParticleShapes.Style("orbit", "spin", "");
    /** Standing still this long (in ticks) before the aura shows. */
    private static final int STILL_TICKS = 10;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final long WARN_EVERY_MS = 3000;

    private final DiscordLinkedBackend plugin;
    /** Null without LuckPerms. */
    private final LuckPermsPrefixes luckPerms;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> trails = new ConcurrentHashMap<>();
    /** Display names from before we added a prefix (only without LuckPerms). */
    private final Map<UUID, Component> originalNames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();

    Cosmetics(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
        LuckPermsPrefixes hook = null;
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                hook = new LuckPermsPrefixes();
                plugin.getLogger().info("Bought prefixes are shown through LuckPerms.");
            } catch (LinkageError | IllegalStateException e) {
                plugin.getLogger().warning("LuckPerms is installed but couldn't be used (" + e + "), so bought "
                        + "prefixes go in front of names instead.");
            }
        }
        this.luckPerms = hook;
    }

    /** The proxy says what a player uses now. Runs on the player's own thread. */
    void apply(Player player, State state) {
        UUID uuid = player.getUniqueId();
        State old = states.getOrDefault(uuid, State.NONE);
        states.put(uuid, state);
        if (!old.prefix().equals(state.prefix())) {
            applyPrefix(player, state.prefix());
        }
        if (!old.sameParticles(state) || !trails.containsKey(uuid)) {
            startEffects(player, state);
        }
    }

    // ---- Prefixes ----

    private void applyPrefix(Player player, String prefix) {
        UUID uuid = player.getUniqueId();
        String text = prefix.isEmpty() || !plugin.settings().prefixSpace ? prefix : prefix + " ";
        if (luckPerms != null) {
            if (!luckPerms.set(uuid, text, plugin.settings().prefixPriority)) {
                plugin.getLogger().warning("LuckPerms hasn't loaded " + player.getName() + " yet; their prefix shows "
                        + "once they rejoin.");
            }
            return;
        }
        Component original = originalNames.computeIfAbsent(uuid, id -> player.displayName());
        if (prefix.isEmpty()) {
            player.displayName(original);
            player.playerListName(null);
            originalNames.remove(uuid);
            return;
        }
        Component name = LEGACY.deserialize(text).append(original);
        player.displayName(name);
        player.playerListName(name);
    }

    // ---- Trails ----

    /** Starts (or restarts) the player's trail and aura: the trail while they move, the aura while they stand still. */
    private void startEffects(Player player, State state) {
        UUID uuid = player.getUniqueId();
        ScheduledTask old = trails.remove(uuid);
        if (old != null) {
            old.cancel();
        }
        Effect trail = effect(state.trail(), state.trailStyle(), TRAIL_STYLE);
        Effect aura = effect(state.aura(), state.auraStyle(), AURA_STYLE);
        if (trail == null && aura == null) {
            return;
        }
        Location[] last = {player.getLocation()};
        long[] ticks = {0, 0}; // ticks running, ticks standing still
        long interval = Math.max(1, plugin.settings().trailIntervalTicks);
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> {
            Location now = player.getLocation();
            Location before = last[0];
            last[0] = now;
            ticks[0] += interval;
            boolean moved = !now.getWorld().equals(before.getWorld()) || now.distanceSquared(before) > 0.0025;
            ticks[1] = moved ? 0 : ticks[1] + interval;
            if (hidden(player)) {
                return;
            }
            if (moved && trail != null) {
                draw(player, now, trail, ticks[0]);
            } else if (!moved && aura != null && ticks[1] >= STILL_TICKS) {
                draw(player, now, aura, ticks[0]);
            }
        }, () -> trails.remove(uuid), interval, interval);
        if (task != null) {
            trails.put(uuid, task);
        }
    }

    private Effect effect(String particle, String style, ParticleShapes.Style fallback) {
        Particle p = particle(particle);
        return p == null ? null : new Effect(p, ParticleShapes.Style.parse(style, fallback));
    }

    private void draw(Player player, Location feet, Effect effect, long tick) {
        ParticleShapes.Style style = effect.style();
        Particle particle = effect.particle();
        for (ParticleShapes.Point point : ParticleShapes.points(style, tick, player.getBodyYaw(), plugin.settings().trailParticles)) {
            Location at = feet.clone().add(point.x(), point.y(), point.z());
            // Only dust and potion swirls take a colour; every other particle looks like itself.
            if (particle == Particle.DUST) {
                feet.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(color(style.color(), tick, point.index()), 0.9f));
            } else if (particle == Particle.ENTITY_EFFECT) {
                feet.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, 0, color(style.color(), tick, point.index()));
            } else {
                feet.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, 0);
            }
        }
    }

    /** "#rrggbb", "rainbow" (colours running along the shape and changing over time), or "" (white). */
    private static Color color(String color, long tick, int index) {
        if (color.isEmpty()) {
            return Color.WHITE;
        }
        if (color.equals("rainbow")) {
            float hue = ((tick * 0.02f) + index * 0.07f) % 1f;
            return Color.fromRGB(java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF);
        }
        try {
            return Color.fromRGB(Integer.parseInt(color.substring(1), 16));
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    /**
     * A particle that needs no extra data (or a colour: dust and potion swirls), or null (none, or one this Minecraft
     * version doesn't have).
     */
    private Particle particle(String key) {
        if (key.isEmpty()) {
            return null;
        }
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        Particle particle = namespaced == null ? null : Registry.PARTICLE_TYPE.get(namespaced);
        if (particle == null || particle.getDataType() != Void.class && particle != Particle.DUST
                && particle != Particle.ENTITY_EFFECT) {
            plugin.getLogger().warning("The particle '" + key + "' doesn't exist on this server's Minecraft "
                    + "version (or needs extra data), so it isn't shown.");
            return null;
        }
        return particle;
    }

    /** Spectators, invisible and vanished players leave no trail. */
    private static boolean hidden(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return true;
        }
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ---- KeepInventory ----

    private boolean keepsInventory(Player player) {
        long from = states.getOrDefault(player.getUniqueId(), State.NONE).keepInventoryFrom();
        return from > 0 && System.currentTimeMillis() >= from;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (event.getKeepInventory() || !keepsInventory(player)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        player.sendMessage(plugin.settings().message("keepinv-kept"));
    }

    /** Players with KeepInventory can't hurt other players: melee, arrows, tridents, TNT, potions, pets. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attacker(event.getDamageSource().getCausingEntity(), event.getDamager());
        if (attacker != null && !attacker.equals(victim) && keepsInventory(attacker)) {
            event.setCancelled(true);
            warn(attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attacker(null, event.getCombuster());
        if (attacker != null && !attacker.equals(victim) && keepsInventory(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower) || !keepsInventory(thrower)
                || !harmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && !victim.equals(thrower)) {
                event.setIntensity(victim, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        ProjectileSource source = event.getEntity().getSource();
        if (!(source instanceof Player thrower) || !keepsInventory(thrower)) {
            return;
        }
        List<PotionEffect> effects = new ArrayList<>(event.getEntity().getCustomEffects());
        if (event.getEntity().getBasePotionType() != null) {
            effects.addAll(event.getEntity().getBasePotionType().getPotionEffects());
        }
        if (harmful(effects)) {
            event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim && !victim.equals(thrower));
        }
    }

    /** The player behind some damage: the one causing it, who shot it, or whose pet it is. */
    private static Player attacker(Entity causing, Entity direct) {
        if (causing instanceof Player player) {
            return player;
        }
        if (direct instanceof Player player) {
            return player;
        }
        if (direct instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (direct instanceof Tameable pet && pet.isTamed()) {
            AnimalTamer owner = pet.getOwner();
            return owner instanceof Player player ? player : null;
        }
        return null;
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(e -> e.getType().getCategory() == PotionEffectTypeCategory.HARMFUL);
    }

    private void warn(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last >= WARN_EVERY_MS) {
            lastWarning.put(player.getUniqueId(), now);
            player.sendActionBar(plugin.settings().message("keepinv-no-pvp"));
        }
    }

    // ---- Leaving ----

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        states.remove(uuid);
        lastWarning.remove(uuid);
        originalNames.remove(uuid);
        ScheduledTask trail = trails.remove(uuid);
        if (trail != null) {
            trail.cancel();
        }
        if (luckPerms != null) {
            luckPerms.forget(uuid);
        }
    }
}
