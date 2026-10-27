package com.huzzysnuzzy.discordlinked.fad;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Community features for DiscordLinked: interaction commands like /hug and /kiss, and reporting playtime (minus
 * AFK) and advancements so players earn credits. The Velocity proxy does the rest: it keeps the credits, shows
 * interactions on every server and in Discord (as @mentions), and puts everything on the admin website.
 *
 * <p>Works on Paper and Folia: players are only touched from their own scheduler, and nothing uses the main
 * thread scheduler.
 */
public final class DiscordLinkedFAD extends JavaPlugin {

    private static final String COOLDOWN_BYPASS = "discordlinkedfad.cooldown.bypass";

    private volatile FadSettings settings;
    private ProxyLink link;
    private final SellPrices sellPrices = new SellPrices();
    private Cosmetics cosmetics;
    private final Map<UUID, Long> lastInteraction = new ConcurrentHashMap<>();
    /** Interaction commands registered at startup; new ones in config.yml need a restart. */
    private final Set<String> registered = new HashSet<>();

    @Override
    public void onEnable() {
        settings = FadSettings.load(this);
        link = new ProxyLink(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, ProxyLink.CHANNEL, link);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ProxyLink.CHANNEL);
        registerCommands();
        PlayerTracker tracker = new PlayerTracker(this);
        getServer().getPluginManager().registerEvents(tracker, this);
        tracker.start();
        getServer().getPluginManager().registerEvents(new SellMenu.Events(this), this);
        cosmetics = new Cosmetics(this);
        getServer().getPluginManager().registerEvents(cosmetics, this);
        PluginCommand admin = getCommand("discordlinkedfad");
        if (admin != null) {
            admin.setExecutor((sender, command, label, args) -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    reload(sender);
                    return true;
                }
                return false;
            });
            admin.setTabCompleter((sender, command, label, args) -> args.length == 1 ? List.of("reload") : List.of());
        }
        getLogger().info("Ready with " + settings.actions.size() + " interactions" + (isFolia() ? " (Folia)" : ""));
    }

    @Override
    public void onDisable() {
        SellMenu.closeAll();
        if (link != null) {
            link.cancelAll();
        }
    }

    private void registerCommands() {
        CommandMap map = getServer().getCommandMap();
        for (Action action : settings.actions.values()) {
            addPermission(action);
            register(map, new FadCommands.ActionCommand(this, action));
        }
        register(map, new FadCommands.CreditsCommand(this, settings.creditsCommand));
        register(map, new FadCommands.InteractionsCommand(this, settings.interactionsCommand));
        register(map, new FadCommands.SellCommand(this, settings.sellCommand));
        register(map, new FadCommands.SellHandCommand(this, settings.sellHandCommand));
        register(map, new FadCommands.WorthCommand(this, settings.worthCommand));
        register(map, new FadCommands.MicroTopCommand(this, settings.microTopCommand));
        register(map, new FadCommands.ExchangeCommand(this, settings.exchangeCommand));
    }

    private void register(CommandMap map, Command command) {
        String prefix = getName().toLowerCase(Locale.ROOT);
        if (!map.register(prefix, command)) {
            getLogger().warning("/" + command.getName() + " is already taken by another plugin, so it's only "
                    + "available as /" + prefix + ":" + command.getName() + ". Rename it in config.yml to fix that.");
        }
        registered.add(command.getName());
    }

    /** Everyone may use every interaction unless a permissions plugin takes it away. */
    private void addPermission(Action action) {
        if (getServer().getPluginManager().getPermission(action.permission()) == null) {
            getServer().getPluginManager().addPermission(new Permission(action.permission(),
                    "Use /" + action.name(), PermissionDefault.TRUE));
        }
    }

    private void reload(CommandSender sender) {
        FadSettings fresh = FadSettings.load(this);
        List<String> needRestart = new ArrayList<>();
        for (Action action : fresh.actions.values()) {
            addPermission(action);
            if (!registered.contains(action.name())) {
                needRestart.add("/" + action.name());
            }
        }
        settings = fresh;
        link.resync();
        sender.sendMessage(fresh.message("reloaded",
                Placeholder.unparsed("count", String.valueOf(fresh.actions.size() - needRestart.size()))));
        if (!needRestart.isEmpty()) {
            sender.sendMessage(fresh.message("reloaded-restart", Placeholder.unparsed("names", String.join(", ", needRestart))));
        }
    }

    // ---- Shared by the commands ----

    FadSettings settings() {
        return settings;
    }

    ProxyLink link() {
        return link;
    }

    SellPrices sellPrices() {
        return sellPrices;
    }

    Cosmetics cosmetics() {
        return cosmetics;
    }

    /**
     * Sells items already taken from the player: what can be sold goes to the proxy, the rest is given back right
     * away. Runs on the player's own thread.
     */
    void sell(Player player, List<ItemStack> items) {
        Map<String, Integer> lines = new java.util.LinkedHashMap<>();
        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack stack : items) {
            if (sellPrices.sellable(stack)) {
                lines.merge(stack.getType().getKey().getKey(), stack.getAmount(), Integer::sum);
            } else {
                keep.add(stack);
            }
        }
        if (!keep.isEmpty()) {
            SellMenu.giveBack(player, keep);
            player.sendMessage(settings.message("sell-returned"));
        }
        if (!lines.isEmpty()) {
            link.sell(player, lines);
        }
    }

    /** An online player the sender can see, by exact name first, then by the start of their name. */
    Player findPlayer(Player sender, String name) {
        Player found = Bukkit.getPlayerExact(name);
        if (found == null) {
            found = Bukkit.getPlayer(name);
        }
        return found != null && sender.canSee(found) ? found : null;
    }

    List<String> playerNames(CommandSender sender, String prefix, boolean includeSelf) {
        String start = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean self = player.equals(sender);
            if ((self && !includeSelf) || (sender instanceof Player viewer && !viewer.canSee(player))) {
                continue;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(player.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Seconds the player still has to wait, or 0 after starting a new cooldown. */
    long startCooldown(Player player) {
        long cooldownMs = settings.cooldownSeconds * 1000L;
        if (cooldownMs == 0 || player.hasPermission(COOLDOWN_BYPASS)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Long last = lastInteraction.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            return (cooldownMs - (now - last) + 999) / 1000;
        }
        lastInteraction.put(player.getUniqueId(), now);
        return 0;
    }

    void forgetCooldown(UUID player) {
        lastInteraction.remove(player);
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
