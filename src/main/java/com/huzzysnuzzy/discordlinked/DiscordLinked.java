package com.huzzysnuzzy.discordlinked;

import com.google.inject.Inject;
import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.command.AdminCommand;
import com.huzzysnuzzy.discordlinked.command.PanelCommand;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.link.LinkCodeManager;
import com.huzzysnuzzy.discordlinked.listener.PlayerListener;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.PlayerNote;
import com.huzzysnuzzy.discordlinked.storage.PlayerRef;
import com.huzzysnuzzy.discordlinked.storage.Suspension;
import com.huzzysnuzzy.discordlinked.spy.InventoryRequests;
import com.huzzysnuzzy.discordlinked.spy.SpyManager;
import com.huzzysnuzzy.discordlinked.web.WebServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Plugin(
        id = "discordlinked",
        name = "DiscordLinked",
        version = DiscordLinked.VERSION,
        description = "Requires players to link a Discord account and tracks their activity",
        authors = {"HuzzySnuzzy"}
)
public final class DiscordLinked {

    public static final String VERSION = "0.7.0";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private Messages messages;
    private Database database;
    private LinkCodeManager linkCodes;
    private AuditLog audit;
    private DiscordBot bot;
    private WebServer web;
    private SpyManager spy;
    private InventoryRequests inventories;

    @Inject
    public DiscordLinked(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws Exception {
        config = PluginConfig.load(dataDirectory, logger);
        database = new Database(dataDirectory.resolve("data.db"));
        messages = Messages.load(dataDirectory, logger);
        linkCodes = new LinkCodeManager(Duration.ofMinutes(config.codeExpiryMinutes()));
        audit = new AuditLog(database, messages, logger);
        bot = new DiscordBot(this);
        audit.setDiscordSink(bot::sendLog);
        bot.start();

        proxy.getEventManager().register(this, new PlayerListener(this));
        spy = new SpyManager(this);
        proxy.getChannelRegistrar().register(SpyManager.CHANNEL);
        proxy.getEventManager().register(this, spy);
        inventories = new InventoryRequests();
        proxy.getChannelRegistrar().register(InventoryRequests.CHANNEL);
        proxy.getEventManager().register(this, inventories);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("dlink").plugin(this).build(),
                new AdminCommand(this));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("dla").plugin(this).build(),
                new PanelCommand(this));
        if (config.webEnabled()) {
            web = new WebServer(this);
            if (!web.start()) {
                web = null;
            }
        }
        logger.info("DiscordLinked {} enabled (chat bridge: minecraft->discord={}, discord->minecraft={}, website={})",
                VERSION, config.minecraftToDiscord(), config.discordToMinecraft(), config.webEnabled());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (spy != null) {
            spy.shutdown();
        }
        if (web != null) {
            web.stop();
        }
        if (bot != null) {
            bot.shutdown();
        }
        if (audit != null) {
            audit.shutdown();
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                logger.warn("Error closing database", e);
            }
        }
    }

    /**
     * Removes a link, strips the Discord role and kicks the player if they're online.
     *
     * @param actor        who did it, shown in the logs
     * @param notifyPlayer whether to DM the player (false when they unlinked themselves)
     */
    public boolean unlink(LinkedAccount account, String actor, String reason, boolean notifyPlayer) throws SQLException {
        if (!database.unlink(account.uuid())) {
            return false;
        }
        bot.removeLinkedRole(account.discordId());
        audit.log(account.uuid(), account.minecraftName(), account.discordId(), EventTypes.UNLINK, reason, actor);
        proxy.getPlayer(account.uuid()).ifPresent(player -> player.disconnect(
                messages.minecraft("minecraft.kick.unlinked")));
        if (notifyPlayer) {
            notifyPlayer(PlayerRef.of(account), "discord.dm.unlink", "reason", reason);
        }
        return true;
    }

    /** Blocks the player from joining until the duration passes, and kicks them if online. */
    public Suspension suspend(PlayerRef player, Duration duration, String reason, String actor) throws SQLException {
        Suspension suspension = database.addSuspension(player.uuid(), reason, actor, Instant.now().plus(duration));
        audit.log(player.uuid(), player.name(), player.discordId(), EventTypes.SUSPEND,
                "for " + TimeFormat.duration(duration) + " (until " + TimeFormat.dateTime(suspension.expiresAt()) + "): "
                        + reason, actor);
        proxy.getPlayer(player.uuid()).ifPresent(online -> online.disconnect(suspendedMessage(suspension)));
        notifyPlayer(player, "discord.dm.suspend", "reason", reason, "duration", TimeFormat.duration(duration),
                "expires", discordTimestamp(suspension.expiresAt()));
        return suspension;
    }

    /** Changes the active suspension's reason and sets it to end {@code fromNow}. False if not suspended. */
    public boolean editSuspension(PlayerRef player, Duration fromNow, String reason, String actor) throws SQLException {
        Optional<Suspension> current = database.activeSuspension(player.uuid());
        Instant newEnd = Instant.now().plus(fromNow);
        if (current.isEmpty() || !database.editSuspension(current.get().id(), reason, newEnd)) {
            return false;
        }
        Suspension old = current.get();
        StringBuilder detail = new StringBuilder("ends ").append(TimeFormat.dateTime(old.expiresAt()))
                .append(" -> ").append(TimeFormat.dateTime(newEnd));
        if (!old.reason().equals(reason)) {
            detail.append("; reason \"").append(old.reason()).append("\" -> \"").append(reason).append('"');
        }
        audit.log(player.uuid(), player.name(), player.discordId(), EventTypes.SUSPEND_EDIT, detail.toString(), actor);
        notifyPlayer(player, "discord.dm.suspend-edit", "reason", reason, "duration", TimeFormat.duration(fromNow),
                "expires", discordTimestamp(newEnd));
        return true;
    }

    public boolean unsuspend(PlayerRef player, String reason, String actor) throws SQLException {
        if (!database.liftSuspension(player.uuid(), actor, reason)) {
            return false;
        }
        audit.log(player.uuid(), player.name(), player.discordId(), EventTypes.UNSUSPEND, reason, actor);
        notifyPlayer(player, "discord.dm.unsuspend", "reason", reason);
        return true;
    }

    /** Kicks an online player. Returns false if they aren't online. */
    public boolean kick(PlayerRef player, String reason, String actor) {
        Optional<Player> online = proxy.getPlayer(player.uuid());
        if (online.isEmpty()) {
            return false;
        }
        online.get().disconnect(messages.minecraft("minecraft.kick.kicked", "reason", reason));
        audit.log(player.uuid(), player.name(), player.discordId(), EventTypes.KICK, reason, actor);
        notifyPlayer(player, "discord.dm.kick", "reason", reason);
        return true;
    }

    /** Adds a private staff note. Players are never told about notes. */
    public PlayerNote addNote(PlayerRef player, String note, String actor) throws SQLException {
        PlayerNote added = database.addNote(player.uuid(), note, actor);
        audit.log(player.uuid(), player.name(), player.discordId(), EventTypes.NOTE, note, actor);
        return added;
    }

    /** DMs the player's linked Discord account, if DMs are on and the message isn't blank. */
    private void notifyPlayer(PlayerRef player, String key, String... placeholders) {
        if (!config.dmPlayers() || player.discordId() == null || messages.isEmpty(key)) {
            return;
        }
        String[] values = new String[placeholders.length + 2];
        values[0] = "player";
        values[1] = player.name();
        System.arraycopy(placeholders, 0, values, 2, placeholders.length);
        for (int i = 1; i < values.length; i += 2) {
            if (!values[i - 1].equals("expires")) {
                values[i] = MarkdownSanitizer.escape(values[i]);
            }
        }
        bot.sendDirectMessage(player.discordId(), messages.discord(key, values));
    }

    /** Shown in each reader's own time zone by Discord. */
    private static String discordTimestamp(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ":f>";
    }

    public Component suspendedMessage(Suspension suspension) {
        return messages.minecraft("minecraft.kick.suspended",
                "reason", suspension.reason(),
                "expires", TimeFormat.dateTime(suspension.expiresAt()),
                "remaining", TimeFormat.duration(Duration.between(Instant.now(), suspension.expiresAt())));
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public PluginConfig config() {
        return config;
    }

    /** The admin website, or null when it's turned off or couldn't start. */
    public WebServer web() {
        return web;
    }

    public InventoryRequests inventories() {
        return inventories;
    }

    public SpyManager spy() {
        return spy;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public LinkCodeManager linkCodes() {
        return linkCodes;
    }

    public AuditLog audit() {
        return audit;
    }

    public DiscordBot bot() {
        return bot;
    }
}
