package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.TimeFormat;
import com.huzzysnuzzy.discordlinked.storage.CreditAccount;
import com.huzzysnuzzy.discordlinked.storage.LeaderboardRow;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerRef;
import com.huzzysnuzzy.discordlinked.storage.PlaytimeReward;
import com.velocitypowered.api.proxy.Player;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The proxy side of DiscordLinkedFAD: credits for active playtime and advancements, interactions shown on every
 * server and in Discord, and opting out of them. The Minecraft servers report what happens (see {@link FadBridge});
 * everything is stored and decided here, so it's the same on every server and on Discord.
 */
public final class Community {

    private static final long TICK_SECONDS = 60;
    private static final int TOP_SIZE = 10;

    private final DiscordLinked plugin;
    private final FadActions actions;
    private final FadBridge bridge;
    private final FadDiscord discord;
    /** Players their server reported as AFK. Cleared when they switch server or leave. */
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    /** Servers running DiscordLinkedFAD: name -> version. */
    private final Map<String, String> servers = new ConcurrentHashMap<>();

    public Community(DiscordLinked plugin) {
        this.plugin = plugin;
        this.actions = new FadActions(plugin.dataDirectory(), plugin.logger());
        this.bridge = new FadBridge(plugin, this);
        this.discord = new FadDiscord(plugin, this);
    }

    public void start() {
        plugin.proxy().getChannelRegistrar().register(FadBridge.CHANNEL);
        plugin.proxy().getEventManager().register(plugin, bridge);
        plugin.proxy().getScheduler().buildTask(plugin, this::tickPlaytime)
                .delay(TICK_SECONDS, TimeUnit.SECONDS).repeat(TICK_SECONDS, TimeUnit.SECONDS).schedule();
    }

    public FadActions actions() {
        return actions;
    }

    public FadDiscord discord() {
        return discord;
    }

    FadBridge bridge() {
        return bridge;
    }

    /** Servers that have said hello since the proxy started, with their DiscordLinkedFAD version. */
    public Map<String, String> servers() {
        return Map.copyOf(servers);
    }

    private PluginConfig config() {
        return plugin.config();
    }

    // ---- From the Minecraft servers ----

    /** A server started (or reloaded) DiscordLinkedFAD and sent its interactions. */
    void hello(String server, String version, String json) {
        servers.put(server, version);
        if (actions.update(json)) {
            plugin.logger().info("Got {} interactions from {} (DiscordLinkedFAD {})",
                    actions.current().actions().size(), server, version);
            plugin.bot().refreshCommands();
        }
    }

    void setAfk(UUID player, boolean isAfk) {
        if (isAfk) {
            afk.add(player);
        } else {
            afk.remove(player);
        }
    }

    void clearAfk(UUID player) {
        afk.remove(player);
    }

    /** Every minute: playtime for everyone on a server, and credits for whoever has played long enough. */
    private void tickPlaytime() {
        PluginConfig c = config();
        if (!c.fadEnabled()) {
            return;
        }
        Map<UUID, String> active = new HashMap<>();
        Map<UUID, String> away = new HashMap<>();
        for (Player player : plugin.proxy().getAllPlayers()) {
            if (player.getCurrentServer().isPresent()) {
                (afk.contains(player.getUniqueId()) ? away : active).put(player.getUniqueId(), player.getUsername());
            }
        }
        if (active.isEmpty() && away.isEmpty()) {
            return;
        }
        List<PlaytimeReward> rewards;
        try {
            rewards = plugin.database().addPlaytime(active, away, TICK_SECONDS, c.creditsPlaytimeMinutes() * 60L,
                    c.creditsPlaytimeAmount(), LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (SQLException e) {
            plugin.logger().error("Could not record playtime", e);
            return;
        }
        if (plugin.messages().isEmpty("minecraft.fad.playtime-reward")) {
            return;
        }
        for (PlaytimeReward reward : rewards) {
            plugin.proxy().getPlayer(reward.uuid()).ifPresent(player -> player.sendActionBar(plugin.messages().minecraft(
                    "minecraft.fad.playtime-reward", "amount", c.credits(reward.amount()), "balance", c.credits(reward.balance()))));
        }
    }

    /** A player made an advancement; credits and announcements only the first time, on any server. */
    void advancement(Player player, String server, String key, String title, String frame) {
        PluginConfig c = config();
        if (!c.fadEnabled()) {
            return;
        }
        long amount = Math.max(0, switch (frame) {
            case "CHALLENGE" -> c.creditsAdvancementChallenge();
            case "GOAL" -> c.creditsAdvancementGoal();
            default -> c.creditsAdvancementTask();
        });
        UUID uuid = player.getUniqueId();
        Long discordId = linkedDiscordId(uuid);
        try {
            if (!plugin.database().addAdvancement(uuid, key, title, frame, server, amount)) {
                return;
            }
            if (amount > 0) {
                plugin.database().addCredits(uuid, player.getUsername(), amount, "ADVANCEMENT", title, null);
            }
        } catch (SQLException e) {
            plugin.logger().error("Could not record an advancement for {}", player.getUsername(), e);
            return;
        }
        plugin.audit().log(uuid, player.getUsername(), discordId, EventTypes.ADVANCEMENT,
                title + (amount > 0 ? " (+" + c.credits(amount) + ")" : ""));
        if (amount > 0 && !plugin.messages().isEmpty("minecraft.fad.advancement-reward")) {
            player.sendMessage(plugin.messages().minecraft("minecraft.fad.advancement-reward",
                    "amount", c.credits(amount), "advancement", title));
        }
        if (c.fadDiscordAdvancements()) {
            String messageKey = amount > 0 ? "discord.fad.advancement" : "discord.fad.advancement-no-reward";
            if (!plugin.messages().isEmpty(messageKey)) {
                plugin.bot().post(c.fadChannel(), plugin.messages().discord(messageKey,
                        "player", MarkdownSanitizer.escape(player.getUsername()),
                        "discord", discordId == null ? "" : "<@" + discordId + ">",
                        "advancement", MarkdownSanitizer.escape(title),
                        "amount", MarkdownSanitizer.escape(c.credits(amount))), null);
            }
        }
    }

    /**
     * Someone used an interaction in game. Returns true if it was shown (the server then plays its effects), or
     * false after telling the player why not.
     */
    boolean interactFromGame(Player actor, String server, String action, UUID targetId, String line, String format,
                             String nameFormat) {
        if (!config().fadEnabled()) {
            actor.sendMessage(plugin.messages().minecraft("minecraft.fad.disabled"));
            return false;
        }
        Player target = null;
        if (targetId != null) {
            target = plugin.proxy().getPlayer(targetId).orElse(null);
            if (target == null) {
                return false; // left in the meantime
            }
        }
        Long actorDiscord = linkedDiscordId(actor.getUniqueId());
        Long targetDiscord = target == null ? null : linkedDiscordId(targetId);
        String targetName = target == null ? null : target.getUsername();
        if (target != null && !targetId.equals(actor.getUniqueId()) && isOptedOut(targetId, targetDiscord)) {
            actor.sendMessage(plugin.messages().minecraft("minecraft.fad.opted-out", "player", targetName));
            return false;
        }
        plugin.proxy().sendMessage(FadActions.minecraft(format, nameFormat, line, actor.getUsername(), targetName));
        record(action, actor.getUniqueId(), actorDiscord, actor.getUsername(), targetId, targetDiscord, targetName,
                server, FadActions.plain(line, actor.getUsername(), targetName), null);
        if (config().fadDiscordInteractions()) {
            postInteraction(action, FadActions.discord(line, FadActions.discordName(actorDiscord, actor.getUsername()),
                    target == null ? null : FadActions.discordName(targetDiscord, targetName)), targetDiscord);
        }
        return true;
    }

    /** Posts an interaction to the community channel, pinging the target only if fad-discord-ping is on. */
    void postInteraction(String action, String text, Long targetDiscord) {
        if (plugin.messages().isEmpty("discord.fad.interaction")) {
            return;
        }
        plugin.bot().post(config().fadChannel(), plugin.messages().discord("discord.fad.interaction",
                "message", text, "action", action), config().fadDiscordPing() ? targetDiscord : null);
    }

    /** Stores an interaction for the website and logs it. {@code via} is "Discord" when it came from there. */
    void record(String action, UUID actorUuid, Long actorDiscord, String actorName, UUID targetUuid, Long targetDiscord,
                String targetName, String source, String text, String via) {
        try {
            plugin.database().addInteraction(action, actorUuid, actorDiscord, actorName, targetUuid, targetDiscord,
                    targetName, source, text);
        } catch (SQLException e) {
            plugin.logger().warn("Could not store an interaction", e);
        }
        String detail = (via == null ? "" : "[" + via + "] ") + "/" + action + ": " + text;
        if (actorUuid != null) {
            plugin.audit().log(actorUuid, actorName, actorDiscord, EventTypes.INTERACTION, detail);
        } else {
            plugin.audit().log(null, null, actorDiscord, EventTypes.INTERACTION, detail, actorName + " (Discord)");
        }
    }

    /** /credits, /credits <player> or /credits top, answered in chat. */
    void credits(Player player, String query) {
        PluginConfig c = config();
        if (!c.fadEnabled()) {
            player.sendMessage(plugin.messages().minecraft("minecraft.fad.disabled"));
            return;
        }
        try {
            if (query.equalsIgnoreCase("top")) {
                List<LeaderboardRow> top = plugin.database().leaderboard("credits", TOP_SIZE);
                if (top.isEmpty()) {
                    player.sendMessage(plugin.messages().minecraft("minecraft.fad.top-empty", "currency", c.creditsNamePlural()));
                    return;
                }
                player.sendMessage(plugin.messages().minecraft("minecraft.fad.top-header", "currency", c.creditsNamePlural()));
                for (int i = 0; i < top.size(); i++) {
                    player.sendMessage(plugin.messages().minecraft("minecraft.fad.top-line", "rank", String.valueOf(i + 1),
                            "player", top.get(i).name(), "balance", c.credits(top.get(i).credits())));
                }
                return;
            }
            PlayerRef who = query.isEmpty() ? new PlayerRef(player.getUniqueId(), player.getUsername(), null) : findByName(query);
            if (who == null) {
                player.sendMessage(plugin.messages().minecraft("minecraft.fad.not-found", "player", query));
                return;
            }
            long balance = plugin.database().creditAccount(who.uuid()).map(CreditAccount::credits).orElse(0L);
            long rank = plugin.database().creditRank(who.uuid());
            boolean self = who.uuid().equals(player.getUniqueId());
            player.sendMessage(plugin.messages().minecraft(self ? "minecraft.fad.balance" : "minecraft.fad.balance-other",
                    "player", who.name(), "balance", c.credits(balance), "rank", rank == 0 ? "-" : String.valueOf(rank)));
        } catch (SQLException e) {
            plugin.logger().error("Database error in /credits", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
        }
    }

    /** A player online now, else anyone with credits or a link, by name. Null if unknown. */
    private PlayerRef findByName(String name) throws SQLException {
        Optional<Player> online = plugin.proxy().getPlayer(name);
        if (online.isPresent()) {
            return new PlayerRef(online.get().getUniqueId(), online.get().getUsername(), null);
        }
        Optional<CreditAccount> account = plugin.database().creditAccountByName(name);
        if (account.isPresent()) {
            return new PlayerRef(account.get().uuid(), account.get().name(), null);
        }
        return plugin.database().findByName(name).map(PlayerRef::of).orElse(null);
    }

    /** /interactions off|on in game. Applies to the linked Discord account too. */
    void toggle(Player player, boolean enabled) {
        Long discordId = linkedDiscordId(player.getUniqueId());
        try {
            plugin.database().setOptOut(subjects(player.getUniqueId(), discordId), !enabled);
        } catch (SQLException e) {
            plugin.logger().error("Database error in /interactions", e);
            player.sendMessage(plugin.messages().minecraft("minecraft.command.database-error"));
            return;
        }
        player.sendMessage(plugin.messages().minecraft(enabled ? "minecraft.fad.interactions-on" : "minecraft.fad.interactions-off"));
    }

    // ---- Shared with Discord and the website ----

    /** Whether this person turned interactions off, in game or on Discord. Either id may be null. */
    public boolean isOptedOut(UUID uuid, Long discordId) {
        try {
            return plugin.database().isOptedOut(subjects(uuid, discordId));
        } catch (SQLException e) {
            plugin.logger().warn("Could not check interaction opt-outs", e);
            return false;
        }
    }

    static List<String> subjects(UUID uuid, Long discordId) {
        List<String> subjects = new ArrayList<>();
        if (uuid != null) {
            subjects.add("mc:" + uuid);
        }
        if (discordId != null) {
            subjects.add("dc:" + discordId);
        }
        return subjects;
    }

    Long linkedDiscordId(UUID uuid) {
        try {
            return plugin.database().findByUuid(uuid).map(LinkedAccount::discordId).orElse(null);
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Gives or takes credits from the website. Returns the new balance, or -1 (nothing changed) if the player
     * doesn't have enough.
     */
    public long adjust(PlayerRef player, long amount, String reason, String actor) throws SQLException {
        PluginConfig c = config();
        long balance = plugin.database().addCredits(player.uuid(), player.name(), amount, "ADMIN", reason, actor);
        if (balance < 0) {
            return -1;
        }
        String signed = (amount > 0 ? "+" : "-") + c.credits(Math.abs(amount));
        plugin.audit().log(player.uuid(), player.name(), player.discordId(), EventTypes.CREDITS_ADJUST,
                signed + ", now " + c.credits(balance) + ": " + reason, actor);
        plugin.proxy().getPlayer(player.uuid()).ifPresent(online -> {
            if (!plugin.messages().isEmpty("minecraft.fad.adjusted")) {
                online.sendMessage(plugin.messages().minecraft("minecraft.fad.adjusted",
                        "amount", signed, "balance", c.credits(balance), "reason", reason));
            }
        });
        return balance;
    }

    /** "76h 12m" of active play. */
    static String playtime(long seconds) {
        return TimeFormat.hours(Duration.ofSeconds(seconds));
    }

    /** Shown in game for an interaction from Discord. */
    Component fromDiscord(Component line) {
        return plugin.messages().minecraft("minecraft.fad.from-discord", Placeholder.component("message", line));
    }
}
