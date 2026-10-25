package com.huzzysnuzzy.discordlinked;

import com.google.inject.Inject;
import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.command.AdminCommand;
import com.huzzysnuzzy.discordlinked.discord.DiscordBot;
import com.huzzysnuzzy.discordlinked.link.LinkCodeManager;
import com.huzzysnuzzy.discordlinked.listener.PlayerListener;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.LinkedAccount;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;

@Plugin(
        id = "discordlinked",
        name = "DiscordLinked",
        version = "0.1.0",
        description = "Requires players to link a Discord account and tracks their activity",
        authors = {"HuzzySnuzzy"}
)
public final class DiscordLinked {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private Messages messages;
    private Database database;
    private LinkCodeManager linkCodes;
    private AuditLog audit;
    private DiscordBot bot;

    @Inject
    public DiscordLinked(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws Exception {
        config = PluginConfig.load(dataDirectory);
        database = new Database(dataDirectory.resolve("data.db"));
        messages = Messages.load(dataDirectory);
        linkCodes = new LinkCodeManager(Duration.ofMinutes(config.codeExpiryMinutes()));
        audit = new AuditLog(database, messages, logger);
        bot = new DiscordBot(this);
        audit.setDiscordSink(bot::sendLog);
        bot.start();

        proxy.getEventManager().register(this, new PlayerListener(this));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("dlink").plugin(this).build(),
                new AdminCommand(this));
        logger.info("DiscordLinked enabled");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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

    /** Removes a link, strips the Discord role and kicks the player if they're online. */
    public void unlink(LinkedAccount account, String by) throws SQLException {
        if (!database.unlink(account.uuid())) {
            return;
        }
        bot.removeLinkedRole(account.discordId());
        audit.log(account.uuid(), account.minecraftName(), account.discordId(), EventTypes.UNLINK, "by " + by);
        proxy.getPlayer(account.uuid()).ifPresent(player -> player.disconnect(
                messages.minecraft("minecraft.kick.unlinked")));
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
