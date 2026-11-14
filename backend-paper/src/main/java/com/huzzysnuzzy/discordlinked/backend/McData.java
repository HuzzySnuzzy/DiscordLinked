package com.huzzysnuzzy.discordlinked.backend;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Resets parts of players' Minecraft data on this server (website: MC data), and undoes resets.
 *
 * <p>Everything goes through the Bukkit API on the player's own thread, never by editing the world's player files:
 * players online now are reset right away; everyone else when they next join this server (before they can do
 * anything). An operation for everyone stays pending for players who haven't joined since; one that was undone is
 * never applied to them.
 *
 * <p>Before a player's data is reset, what's removed is saved in {@code mcdata/backups/<op>/<uuid>.json} (and the
 * inventory also as an inventory snapshot on the website), so an undo can give it back: advancements, statistics
 * (added to what they have now), items (added to the inventory, the rest dropped at their feet), experience, unlocked
 * recipes and the respawn point. Health and hunger, and where they were standing, aren't given back. Backups are
 * kept for {@link #KEEP_DAYS} days.
 *
 * <p>Files: {@code mcdata/ops.json} (every operation this server got from the proxy) and
 * {@code mcdata/players/<uuid>.json} (which ones a player got and which were undone for them).
 */
final class McData implements Listener {

    /** What can be reset. */
    enum Category {
        ADVANCEMENTS, STATS, INVENTORY, ENDERCHEST, XP, HEALTH, LOCATION, RECIPES;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Set<Category> parse(String list) {
            Set<Category> categories = EnumSet.noneOf(Category.class);
            for (String part : list.split(",")) {
                for (Category category : values()) {
                    if (category.key().equals(part.strip())) {
                        categories.add(category);
                    }
                }
            }
            return categories;
        }
    }

    /** One operation from the proxy. {@code target} null = everyone. */
    static final class Op {
        String id;
        String target;
        List<String> categories = new ArrayList<>();
        long created;
        /** When this server got it (this machine's clock, like players' first-join times). */
        long received;
        String actor;
        boolean undone;

        Set<Category> categorySet() {
            return Category.parse(String.join(",", categories));
        }

        boolean concerns(UUID uuid) {
            return target == null || target.equals(uuid.toString());
        }
    }

    /** Which operations a player got, and which of those were undone for them. */
    static final class PlayerState {
        Set<String> applied = new LinkedHashSet<>();
        Set<String> restored = new LinkedHashSet<>();
        /** Operations for everyone from before this player's first join: nothing of theirs was reset. */
        Set<String> skipped = new LinkedHashSet<>();
    }

    static final int KEEP_DAYS = 30;
    private static final long SUMMARY_MINUTES = 5;
    /** Players who first joined later than this after an operation for everyone was made are left alone. */
    private static final long CLOCK_SLACK = 120_000;
    private static final Gson GSON = new Gson();

    private final DiscordLinkedBackend plugin;
    private final Path directory;
    private final List<Op> ops = new ArrayList<>();
    /** Per operation: how many players got it, and how many had it undone (counted from the files at start). */
    private final Map<String, int[]> counts = new ConcurrentHashMap<>();
    /** Players whose advancements are being given back: no chat announcements, no credits for them. */
    private final Set<UUID> restoring = ConcurrentHashMap.newKeySet();

    McData(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
        this.directory = plugin.getDataFolder().toPath().resolve("mcdata");
        load();
        countFromFiles();
    }

    void start() {
        // Old backups go after KEEP_DAYS days (an undo after that gives back nothing for those players).
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> cleanBackups(), 1, 60 * 6, TimeUnit.MINUTES);
        // The website's summary of everyone's data here, kept fresh while they play.
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sendSummarySoon(player, 1);
            }
        }, SUMMARY_MINUTES, SUMMARY_MINUTES, TimeUnit.MINUTES);
    }

    /** Sends the player's summary to the proxy after {@code ticks}, from their own thread. */
    void sendSummarySoon(Player player, long ticks) {
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline()) {
                try {
                    plugin.link().mcSummary(player, summary(player));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not sum up " + player.getName() + "'s data: " + e);
                }
            }
        }, null, Math.max(1, ticks));
    }

    /** Whether an advancement given back right now should go unannounced and earn nothing. */
    boolean restoring(Player player) {
        return restoring.contains(player.getUniqueId());
    }

    // ---- From the proxy ----

    /**
     * An operation (MCDATA): a reset, or the undo of one. Applied to the players online now, the rest when they
     * join. Answered with its state (MCDATA_STATE) through {@code carrier}. Safe from any thread.
     */
    void receive(Player carrier, String id, String kind, String target, String categories, long created, String actor) {
        Op op;
        synchronized (this) {
            op = find(id);
            if (kind.equals("undo")) {
                if (op == null) {
                    // An undo for something this server never got: remember it as undone, so it's never applied.
                    op = new Op();
                    op.id = id;
                    op.target = target.isEmpty() ? null : target;
                    op.categories = new ArrayList<>(List.of(categories.split(",")));
                    op.created = created;
                    op.received = System.currentTimeMillis();
                    op.actor = actor;
                    ops.add(op);
                }
                op.undone = true;
            } else if (op == null) {
                op = new Op();
                op.id = id;
                op.target = target.isEmpty() ? null : target;
                op.categories = new ArrayList<>(List.of(categories.split(",")));
                op.created = created;
                op.received = System.currentTimeMillis();
                op.actor = actor;
                ops.add(op);
                plugin.getLogger().info("MC data: " + describe(op) + " (by " + actor + ")");
            }
            saveOps();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (op.concerns(player.getUniqueId())) {
                player.getScheduler().run(plugin, task -> catchUp(player), null);
            }
        }
        // The state after the players online now had their turn.
        Op answered = op;
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> plugin.link().mcDataState(carrier, answered,
                count(answered)), 2, TimeUnit.SECONDS);
    }

    private Op find(String id) {
        for (Op op : ops) {
            if (op.id.equals(id)) {
                return op;
            }
        }
        return null;
    }

    /** How many players got it, and how many of them had it undone. */
    int[] count(Op op) {
        int[] count = counts.get(op.id);
        return count == null ? new int[] {0, 0} : count.clone();
    }

    private void countFromFiles() {
        Path players = directory.resolve("players");
        if (!Files.isDirectory(players)) {
            return;
        }
        try (Stream<Path> files = Files.list(players)) {
            for (Path file : files.toList()) {
                PlayerState state = readState(file);
                if (state == null) {
                    continue;
                }
                for (String id : state.applied) {
                    if (state.skipped != null && state.skipped.contains(id)) {
                        continue;
                    }
                    int[] count = counts.computeIfAbsent(id, key -> new int[2]);
                    count[0]++;
                    if (state.restored.contains(id)) {
                        count[1]++;
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not count MC data resets: " + e.getMessage());
        }
    }

    // ---- Joining: whatever happened while they were away ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean due;
        synchronized (this) {
            due = !ops.isEmpty();
        }
        if (due) {
            // Their own thread, at once: before they can use anything that's about to be reset.
            player.getScheduler().run(plugin, task -> catchUp(player), null);
        }
        // Once the channels are registered and the proxy knows them (a few seconds in).
        sendSummarySoon(player, 200);
    }

    /** Applies the operations this player hasn't had yet, and undoes undone ones they had. Player's thread. */
    void catchUp(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        List<Op> todo = new ArrayList<>();
        PlayerState state;
        synchronized (this) {
            state = state(uuid);
            if (state == null) {
                // Better nothing than resetting someone twice (and overwriting their backup with emptiness).
                plugin.getLogger().warning("MC data: " + player.getName() + "'s file in mcdata/players can't be read; "
                        + "no resets or undos for them until it's fixed or deleted.");
                return;
            }
            for (Op op : ops) {
                if (!op.concerns(uuid)) {
                    continue;
                }
                boolean had = state.applied.contains(op.id);
                if ((!op.undone && !had) || (op.undone && had && !state.restored.contains(op.id))) {
                    todo.add(op);
                }
            }
        }
        List<Op> done = new ArrayList<>();
        for (Op op : todo) {
            try {
                if (op.undone) {
                    JsonObject backup = readBackup(op.id, uuid);
                    // Recorded before anything is given back: an undo cut short is never repeated (some things
                    // missing is better than items given twice).
                    state.restored.add(op.id);
                    if (!saveState(uuid, state)) {
                        state.restored.remove(op.id);
                        continue;
                    }
                    bump(op.id, 1);
                    if (backup != null && !state.skipped.contains(op.id)) {
                        restore(player, op, backup);
                    }
                } else if (op.target == null && player.getFirstPlayed() > Math.min(op.received, op.created + CLOCK_SLACK)) {
                    // Everyone's data was reset before this player first joined: there's nothing of theirs to reset.
                    state.applied.add(op.id);
                    state.skipped.add(op.id);
                    saveState(uuid, state);
                    continue;
                } else {
                    Set<Category> categories = op.categorySet();
                    if (categories.contains(Category.INVENTORY)) {
                        // An item held on the cursor goes back into the inventory first, so it's in the backup.
                        player.closeInventory();
                    }
                    // The backup is on disk before anything changes; a reset whose backup can't be saved doesn't happen.
                    JsonObject backup = backup(player, categories);
                    if (!writeBackup(op.id, uuid, backup)) {
                        continue;
                    }
                    state.applied.add(op.id);
                    if (!saveState(uuid, state)) {
                        state.applied.remove(op.id);
                        continue;
                    }
                    bump(op.id, 0);
                    reset(player, categories, backup);
                }
                done.add(op);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("MC data: couldn't " + (op.undone ? "undo " : "apply ") + op.id + " for "
                        + player.getName() + " completely: " + e);
                done.add(op);
            }
        }
        // The proxy's MC data page shows how far each operation got, and the player's summary changed.
        for (Op op : done) {
            plugin.link().mcDataState(player, op, count(op));
        }
        if (!done.isEmpty()) {
            plugin.link().mcSummary(player, summary(player));
        }
    }

    private void bump(String id, int which) {
        counts.compute(id, (key, old) -> {
            int[] count = old == null ? new int[2] : old.clone();
            count[which]++;
            return count;
        });
    }

    // ---- Resetting ----

    /** What a reset of {@code categories} takes away, to give back on undo. Changes nothing. */
    JsonObject backup(Player player, Set<Category> categories) {
        JsonObject backup = new JsonObject();
        backup.addProperty("name", player.getName());
        backup.addProperty("at", System.currentTimeMillis());
        if (categories.contains(Category.INVENTORY) || categories.contains(Category.ENDERCHEST)) {
            backup.addProperty("items", Base64.getEncoder().encodeToString(InventoryCodec.save(player)));
        }
        if (categories.contains(Category.ADVANCEMENTS)) {
            backup.add("advancements", awardedAdvancements(player));
        }
        if (categories.contains(Category.STATS)) {
            JsonArray stats = new JsonArray();
            forEachStat(player, (statistic, qualifier, value) -> {
                JsonArray row = new JsonArray();
                row.add(statistic.name());
                row.add(qualifier == null ? "" : qualifier.name());
                row.add(value);
                stats.add(row);
            });
            backup.add("stats", stats);
        }
        if (categories.contains(Category.XP)) {
            JsonObject xp = new JsonObject();
            xp.addProperty("level", player.getLevel());
            xp.addProperty("exp", player.getExp());
            backup.add("xp", xp);
        }
        if (categories.contains(Category.RECIPES)) {
            JsonArray keys = new JsonArray();
            player.getDiscoveredRecipes().forEach(key -> keys.add(key.toString()));
            backup.add("recipes", keys);
        }
        if (categories.contains(Category.LOCATION)) {
            // The bed or anchor itself (not the spot next to it where they'd appear), so it can be set back.
            Location respawn = player.getPotentialRespawnLocation();
            if (respawn != null && respawn.getWorld() != null) {
                JsonObject at = new JsonObject();
                at.addProperty("world", respawn.getWorld().getName());
                at.addProperty("x", respawn.getX());
                at.addProperty("y", respawn.getY());
                at.addProperty("z", respawn.getZ());
                backup.add("respawn", at);
            }
        }
        return backup;
    }

    /** Resets {@code categories}, after {@link #backup} saved them. Player's thread. */
    void reset(Player player, Set<Category> categories, JsonObject backup) {
        if (categories.contains(Category.INVENTORY) || categories.contains(Category.ENDERCHEST)) {
            // Also as an inventory snapshot, so it shows (and can be restored) on the player's page.
            InventoryBridge inventories = plugin.inventories();
            if (inventories != null) {
                inventories.snapshot(player, "BEFORE_RESET", "before an MC data reset");
            }
        }
        if (backup.has("advancements")) {
            for (Map.Entry<String, JsonElement> entry : backup.getAsJsonObject("advancements").entrySet()) {
                NamespacedKey key = NamespacedKey.fromString(entry.getKey());
                Advancement advancement = key == null ? null : Bukkit.getAdvancement(key);
                if (advancement == null) {
                    continue;
                }
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                for (JsonElement criterion : entry.getValue().getAsJsonArray()) {
                    progress.revokeCriteria(criterion.getAsString());
                }
            }
        }
        if (backup.has("stats")) {
            for (JsonElement element : backup.getAsJsonArray("stats")) {
                StatRow row = statRow(element.getAsJsonArray());
                if (row != null) {
                    setStat(player, row.statistic(), row.qualifier(), 0);
                }
            }
        }
        if (categories.contains(Category.INVENTORY)) {
            PlayerInventory inventory = player.getInventory();
            inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
            inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
            inventory.setItemInOffHand(null);
        }
        if (categories.contains(Category.ENDERCHEST)) {
            player.getEnderChest().clear();
        }
        if (categories.contains(Category.INVENTORY) || categories.contains(Category.ENDERCHEST)) {
            player.updateInventory();
        }
        if (categories.contains(Category.XP)) {
            player.setLevel(0);
            player.setExp(0);
            player.setTotalExperience(0);
        }
        if (categories.contains(Category.RECIPES)) {
            player.undiscoverRecipes(new HashSet<>(player.getDiscoveredRecipes()));
        }
        if (categories.contains(Category.HEALTH)) {
            double maxHealth = 20;
            try {
                AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
                if (max != null) {
                    maxHealth = max.getValue();
                }
            } catch (RuntimeException | LinkageError ignored) {
                // the plain 20 then
            }
            player.setHealth(maxHealth);
            player.setFoodLevel(20);
            player.setSaturation(5);
            player.setExhaustion(0);
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            player.setFireTicks(0);
            player.setRemainingAir(player.getMaximumAir());
            player.setFallDistance(0);
        }
        if (categories.contains(Category.LOCATION)) {
            player.setRespawnLocation(null);
            World main = Bukkit.getWorlds().get(0);
            player.teleportAsync(main.getSpawnLocation());
        }
        player.sendMessage(plugin.settings().message("mcdata-reset"));
    }

    private record StatRow(Statistic statistic, Enum<?> qualifier, int value) {
    }

    /** A saved [statistic, material or entity or "", value]; null if this server doesn't know it. */
    private static StatRow statRow(JsonArray row) {
        Statistic statistic;
        try {
            statistic = Statistic.valueOf(row.get(0).getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String qualifierName = row.get(1).getAsString();
        Enum<?> qualifier = null;
        if (!qualifierName.isEmpty()) {
            try {
                qualifier = statistic.getType() == Statistic.Type.ENTITY ? enumOrNull(EntityType.class, qualifierName)
                        : Material.getMaterial(qualifierName);
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
            if (qualifier == null) {
                return null;
            }
        }
        return new StatRow(statistic, qualifier, row.get(2).getAsInt());
    }

    /** Every awarded criterion, by advancement. */
    private JsonObject awardedAdvancements(Player player) {
        JsonObject had = new JsonObject();
        Iterator<Advancement> all = Bukkit.advancementIterator();
        while (all.hasNext()) {
            Advancement advancement = all.next();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            Collection<String> awarded = new ArrayList<>(progress.getAwardedCriteria());
            if (awarded.isEmpty()) {
                continue;
            }
            JsonArray criteria = new JsonArray();
            for (String criterion : awarded) {
                criteria.add(criterion);
            }
            had.add(advancement.getKey().toString(), criteria);
        }
        return had;
    }

    private interface StatVisitor {
        void visit(Statistic statistic, Enum<?> qualifier, int value);
    }

    /** Every statistic the player has that isn't 0. Statistics that don't apply to a material or entity are skipped. */
    private static void forEachStat(Player player, StatVisitor visitor) {
        for (Statistic statistic : Statistic.values()) {
            try {
                switch (statistic.getType()) {
                    case UNTYPED -> {
                        int value = player.getStatistic(statistic);
                        if (value != 0) {
                            visitor.visit(statistic, null, value);
                        }
                    }
                    case BLOCK, ITEM -> {
                        boolean blocks = statistic.getType() == Statistic.Type.BLOCK;
                        for (Material material : Material.values()) {
                            if (material.isLegacy() || (blocks ? !material.isBlock() : !material.isItem())) {
                                continue;
                            }
                            try {
                                int value = player.getStatistic(statistic, material);
                                if (value != 0) {
                                    visitor.visit(statistic, material, value);
                                }
                            } catch (IllegalArgumentException ignored) {
                                // not a statistic this material has
                            }
                        }
                    }
                    case ENTITY -> {
                        for (EntityType type : EntityType.values()) {
                            if (type == EntityType.UNKNOWN) {
                                continue;
                            }
                            try {
                                int value = player.getStatistic(statistic, type);
                                if (value != 0) {
                                    visitor.visit(statistic, type, value);
                                }
                            } catch (IllegalArgumentException ignored) {
                                // not a statistic this entity has
                            }
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // a statistic this server doesn't have (or materials and entities can't be listed)
            }
        }
    }

    private static void setStat(Player player, Statistic statistic, Enum<?> qualifier, int value) {
        try {
            if (qualifier instanceof Material material) {
                player.setStatistic(statistic, material, value);
            } else if (qualifier instanceof EntityType type) {
                player.setStatistic(statistic, type, value);
            } else {
                player.setStatistic(statistic, value);
            }
        } catch (IllegalArgumentException ignored) {
            // can't be set
        }
    }

    // ---- Undoing ----

    void restore(Player player, Op op, JsonObject backup) {
        if (backup.has("advancements")) {
            // Completing an advancement again would pay its rewards again (experience): what they have is kept.
            int level = player.getLevel();
            float exp = player.getExp();
            int total = player.getTotalExperience();
            restoring.add(player.getUniqueId());
            try {
                for (Map.Entry<String, JsonElement> entry : backup.getAsJsonObject("advancements").entrySet()) {
                    NamespacedKey key = NamespacedKey.fromString(entry.getKey());
                    Advancement advancement = key == null ? null : Bukkit.getAdvancement(key);
                    if (advancement == null) {
                        continue;
                    }
                    AdvancementProgress progress = player.getAdvancementProgress(advancement);
                    for (JsonElement criterion : entry.getValue().getAsJsonArray()) {
                        progress.awardCriteria(criterion.getAsString());
                    }
                }
            } finally {
                restoring.remove(player.getUniqueId());
                player.setLevel(level);
                player.setExp(exp);
                player.setTotalExperience(total);
            }
        }
        if (backup.has("stats")) {
            for (JsonElement element : backup.getAsJsonArray("stats")) {
                StatRow row = statRow(element.getAsJsonArray());
                if (row == null) {
                    continue;
                }
                // Added to what they have now, so what happened since the reset still counts.
                int now = getStat(player, row.statistic(), row.qualifier());
                setStat(player, row.statistic(), row.qualifier(), (int) Math.min(Integer.MAX_VALUE, (long) now + row.value()));
            }
        }
        if (backup.has("items")) {
            try {
                InventoryCodec.Items items = InventoryCodec.read(Base64.getDecoder().decode(backup.get("items").getAsString()));
                Set<Category> categories = op.categorySet();
                List<ItemStack> back = new ArrayList<>();
                if (categories.contains(Category.INVENTORY)) {
                    PlayerInventory inventory = player.getInventory();
                    ItemStack[] armor = inventory.getArmorContents();
                    for (int i = 0; i < armor.length && i < items.armor().length; i++) {
                        if ((armor[i] == null || armor[i].getType().isAir()) && items.armor()[i] != null) {
                            armor[i] = items.armor()[i];
                        } else if (items.armor()[i] != null) {
                            back.add(items.armor()[i]);
                        }
                    }
                    inventory.setArmorContents(armor);
                    for (ItemStack stack : items.offhand()) {
                        if (stack == null) {
                            continue;
                        }
                        if (inventory.getItemInOffHand().getType().isAir()) {
                            inventory.setItemInOffHand(stack);
                        } else {
                            back.add(stack);
                        }
                    }
                    for (ItemStack stack : items.storage()) {
                        if (stack != null) {
                            back.add(stack);
                        }
                    }
                }
                List<ItemStack> leftover = new ArrayList<>();
                if (categories.contains(Category.ENDERCHEST)) {
                    List<ItemStack> ender = new ArrayList<>();
                    for (ItemStack stack : items.ender()) {
                        if (stack != null) {
                            ender.add(stack);
                        }
                    }
                    leftover.addAll(player.getEnderChest().addItem(ender.toArray(new ItemStack[0])).values());
                }
                back.addAll(leftover);
                for (ItemStack stack : player.getInventory().addItem(back.toArray(new ItemStack[0])).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                }
                player.updateInventory();
            } catch (IOException | IllegalArgumentException e) {
                plugin.getLogger().warning("MC data: couldn't give back " + player.getName() + "'s items: " + e);
            }
        }
        if (backup.has("xp")) {
            JsonObject xp = backup.getAsJsonObject("xp");
            player.giveExp(experience(xp.get("level").getAsInt(), xp.get("exp").getAsFloat()));
        }
        if (backup.has("recipes")) {
            List<NamespacedKey> keys = new ArrayList<>();
            for (JsonElement key : backup.getAsJsonArray("recipes")) {
                NamespacedKey parsed = NamespacedKey.fromString(key.getAsString());
                if (parsed != null) {
                    keys.add(parsed);
                }
            }
            player.discoverRecipes(keys);
        }
        if (backup.has("respawn") && player.getRespawnLocation() == null) {
            JsonObject at = backup.getAsJsonObject("respawn");
            World world = Bukkit.getWorld(at.get("world").getAsString());
            if (world != null) {
                // Forced: it's where their bed or anchor was (the game checks it's still there when they respawn).
                player.setRespawnLocation(new Location(world, at.get("x").getAsDouble(), at.get("y").getAsDouble(),
                        at.get("z").getAsDouble()), true);
            }
        }
        player.sendMessage(plugin.settings().message("mcdata-restored"));
    }

    private static int getStat(Player player, Statistic statistic, Enum<?> qualifier) {
        try {
            if (qualifier instanceof Material material) {
                return player.getStatistic(statistic, material);
            }
            if (qualifier instanceof EntityType type) {
                return player.getStatistic(statistic, type);
            }
            return player.getStatistic(statistic);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Experience points for a level and progress into the next one (Minecraft's formula). */
    static int experience(int level, float progress) {
        long points;
        if (level <= 16) {
            points = (long) level * level + 6L * level;
        } else if (level <= 31) {
            points = Math.round(2.5 * level * level - 40.5 * level + 360);
        } else {
            points = Math.round(4.5 * level * level - 162.5 * level + 2220);
        }
        int toNext = level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
        points += Math.round(Math.max(0, Math.min(1, progress)) * toNext);
        return (int) Math.min(Integer.MAX_VALUE, points);
    }

    /** Announcements of advancements given back by an undo are left out (Paper: message(null)). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (restoring(event.getPlayer())) {
            event.message(null);
        }
    }

    // ---- What the website shows: a summary of a player's data here ----

    /** Advancements made, level and some statistics, as JSON. Player's thread. */
    JsonObject summary(Player player) {
        JsonObject json = new JsonObject();
        int done = 0;
        int total = 0;
        Iterator<Advancement> all = Bukkit.advancementIterator();
        while (all.hasNext()) {
            Advancement advancement = all.next();
            // Only real advancements: recipe unlocks and hidden helpers have no display.
            if (advancement.getDisplay() == null || !advancement.getDisplay().doesAnnounceToChat()) {
                continue;
            }
            total++;
            if (player.getAdvancementProgress(advancement).isDone()) {
                done++;
            }
        }
        json.addProperty("advancements", done);
        json.addProperty("advancementsTotal", total);
        json.addProperty("level", player.getLevel());
        json.addProperty("recipes", player.getDiscoveredRecipes().size());
        long[] blocksMined = {0};
        long[] distance = {0};
        Map<String, Long> untyped = new HashMap<>();
        forEachStat(player, (statistic, qualifier, value) -> {
            if (statistic == Statistic.MINE_BLOCK) {
                blocksMined[0] += value;
            } else if (qualifier == null) {
                untyped.put(statistic.name(), (long) value);
                if (statistic.name().endsWith("_ONE_CM")) {
                    distance[0] += value;
                }
            }
        });
        json.addProperty("playMinutes", untyped.getOrDefault("PLAY_ONE_MINUTE", 0L) / 20 / 60);
        json.addProperty("deaths", untyped.getOrDefault("DEATHS", 0L));
        json.addProperty("mobKills", untyped.getOrDefault("MOB_KILLS", 0L));
        json.addProperty("playerKills", untyped.getOrDefault("PLAYER_KILLS", 0L));
        json.addProperty("jumps", untyped.getOrDefault("JUMP", 0L));
        json.addProperty("blocksMined", blocksMined[0]);
        json.addProperty("distanceKm", Math.round(distance[0] / 1000.0) / 100.0);
        int items = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                items++;
            }
        }
        json.addProperty("inventoryStacks", items);
        int ender = 0;
        for (ItemStack stack : player.getEnderChest().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                ender++;
            }
        }
        json.addProperty("enderStacks", ender);
        return json;
    }

    // ---- Files ----

    private void load() {
        Path file = directory.resolve("ops.json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            Op[] loaded = GSON.fromJson(Files.readString(file), Op[].class);
            if (loaded != null) {
                ops.addAll(List.of(loaded));
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("Could not read " + file + " (MC data resets): " + e.getMessage());
        }
    }

    private void saveOps() {
        try {
            write(directory.resolve("ops.json"), GSON.toJson(ops));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save mcdata/ops.json: " + e.getMessage());
        }
    }

    /** A new state if they have no file; null if it can't be read. */
    private PlayerState state(UUID uuid) {
        Path file = directory.resolve("players").resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return new PlayerState();
        }
        PlayerState state = readState(file);
        if (state != null && state.skipped == null) {
            state.skipped = new LinkedHashSet<>(); // files from before skipped was kept
        }
        return state;
    }

    private static PlayerState readState(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file), PlayerState.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** False (and logged) if it couldn't be saved. */
    private synchronized boolean saveState(UUID uuid, PlayerState state) {
        try {
            write(directory.resolve("players").resolve(uuid + ".json"), GSON.toJson(state));
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("MC data: couldn't save " + uuid + "'s state: " + e.getMessage());
            return false;
        }
    }

    /** Saves a backup; an existing one is kept (it's the first, real one). False (and logged) if it couldn't be. */
    private boolean writeBackup(String op, UUID uuid, JsonObject backup) {
        Path file = directory.resolve("backups").resolve(safe(op)).resolve(uuid + ".json");
        if (Files.exists(file)) {
            return true;
        }
        try {
            write(file, backup.toString());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("MC data: couldn't save the backup for " + op + " (" + uuid + "), so it isn't "
                    + "applied to them: " + e.getMessage());
            return false;
        }
    }

    private JsonObject readBackup(String op, UUID uuid) {
        Path file = directory.resolve("backups").resolve(safe(op)).resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** Operation ids come from the proxy: only letters, digits and dashes reach a file name. */
    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9-]", "_");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanBackups() {
        Path backups = directory.resolve("backups");
        if (!Files.isDirectory(backups)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(KEEP_DAYS).toMillis();
        try (Stream<Path> folders = Files.list(backups)) {
            for (Path folder : folders.toList()) {
                if (Files.getLastModifiedTime(folder).toMillis() >= cutoff) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : files.toList()) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(folder);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not clean up old MC data backups: " + e.getMessage());
        }
    }

    private static String describe(Op op) {
        return "reset " + String.join(", ", op.categories) + " of " + (op.target == null ? "everyone" : op.target);
    }
}
