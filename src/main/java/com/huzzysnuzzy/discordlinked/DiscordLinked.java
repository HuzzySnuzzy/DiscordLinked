package com.huzzysnuzzy.discordlinked;

import com.google.inject.Inject;
import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.command.AdminCommand;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.link.LinkCodeManager;
import com.huzzysnuzzy.discordlinked.listener.PlayerListener;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.huzzysnuzzy.discordlinked.storage.Suspension;
import com.huzzysnuzzy.discordlinked.web.WebServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

@Plugin(
        id = "discordlinked",
        name = "DiscordLinked",
        version = DiscordLinked.VERSION,
        description = "Requires players to link a Discord account and tracks their activity",
        authors = {"HuzzySnuzzy"}
)
public final class DiscordLinked {

    public static final String VERSION = "0.3.0";

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
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("dlink").plugin(this).build(),
                new AdminCommand(this));
        if (config.webEnabled()) {
            web = new WebServer(this);
            web.start();
        }
        logger.info("DiscordLinked {} enabled (chat bridge: minecraft->discord={}, discord->minecraft={}, website={})",
                VERSION, config.minecraftToDiscord(), config.discordToMinecraft(), config.webEnabled());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
     * @param actor who did it, shown in the logs
     */
    public boolean unlink(LinkedAccount account, String actor, String reason) throws SQLException {
        if (!database.unlink(account.uuid())) {
            return false;
        }
        bot.removeLinkedRole(account.discordId());
        audit.log(account.uuid(), account.minecraftName(), account.discordId(), EventTypes.UNLINK, reason, actor);
        proxy.getPlayer(account.uuid()).ifPresent(player -> player.disconnect(
                messages.minecraft("minecraft.kick.unlinked")));
        return true;
    }

    /** Blocks the player from joining until the duration passes, and kicks them if online. */
    public Suspension suspend(LinkedAccount account, Duration duration, String reason, String actor) throws SQLException {
        Suspension suspension = database.addSuspension(account.uuid(), reason, actor, Instant.now().plus(duration));
        audit.log(account.uuid(), account.minecraftName(), account.discordId(), EventTypes.SUSPEND,
                "for " + TimeFormat.duration(duration) + " (until " + TimeFormat.dateTime(suspension.expiresAt()) + "): "
                        + reason, actor);
        proxy.getPlayer(account.uuid()).ifPresent(player -> player.disconnect(suspendedMessage(suspension)));
        return suspension;
    }

    public boolean unsuspend(LinkedAccount account, String reason, String actor) throws SQLException {
        if (!database.liftSuspension(account.uuid(), actor, reason)) {
            return false;
        }
        audit.log(account.uuid(), account.minecraftName(), account.discordId(), EventTypes.UNSUSPEND, reason, actor);
        return true;
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
