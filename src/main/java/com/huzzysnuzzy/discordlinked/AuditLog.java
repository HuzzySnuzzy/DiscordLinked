package com.huzzysnuzzy.discordlinked;

import com.huzzysnuzzy.discordlinked.storage.Database;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Records player activity to the database off the event threads,
 * and mirrors important events to the Discord log channel.
 */
public final class AuditLog {

    /** Events worth posting to the Discord log channel; chat and commands stay in the database only. */
    private static final Set<String> DISCORD_EVENTS = Set.of(
            EventTypes.LINK, EventTypes.UNLINK, EventTypes.JOIN, EventTypes.LEAVE, EventTypes.DENIED_UNLINKED,
            EventTypes.SUSPEND, EventTypes.SUSPEND_EDIT, EventTypes.UNSUSPEND, EventTypes.KICK, EventTypes.NOTE,
            EventTypes.SPY_START, EventTypes.SPY_END, EventTypes.INVENTORY_RESTORE, EventTypes.CREDITS_ADJUST);

    private final Database database;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DiscordLinked-Audit");
        thread.setDaemon(true);
        return thread;
    });
    private final Messages messages;
    private final LiveEvents live = new LiveEvents();
    private volatile Consumer<String> discordSink = message -> { };

    public AuditLog(Database database, Messages messages, Logger logger) {
        this.database = database;
        this.messages = messages;
        this.logger = logger;
    }

    public LiveEvents live() {
        return live;
    }

    public void setDiscordSink(Consumer<String> discordSink) {
        this.discordSink = discordSink;
    }

    public void log(UUID uuid, String minecraftName, Long discordId, String eventType, String detail) {
        log(uuid, minecraftName, discordId, eventType, detail, null);
    }

    /** @param actor who performed an admin action, e.g. "Name (discord id)"; null for player activity */
    public void log(UUID uuid, String minecraftName, Long discordId, String eventType, String detail, String actor) {
        executor.execute(() -> {
            try {
                live.log(database.logEvent(uuid, minecraftName, discordId, eventType, detail, actor));
            } catch (SQLException e) {
                logger.error("Failed to write audit event {} for {}", eventType, minecraftName, e);
            }
        });
        String key = "discord.log." + eventType.toLowerCase();
        if (DISCORD_EVENTS.contains(eventType) && !messages.isEmpty(key)) {
            discordSink.accept(messages.discord(key,
                    "player", minecraftName == null ? "-" : MarkdownSanitizer.escape(minecraftName),
                    "actor", actor == null ? "-" : MarkdownSanitizer.escape(actor),
                    "discord", discordId == null ? "-" : "<@" + discordId + ">",
                    "detail", detail == null ? "" : MarkdownSanitizer.escape(detail)));
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class EventTypes {
        public static final String LINK = "LINK";
        public static final String UNLINK = "UNLINK";
        public static final String JOIN = "JOIN";
        public static final String LEAVE = "LEAVE";
        public static final String SERVER_SWITCH = "SERVER_SWITCH";
        public static final String CHAT = "CHAT";
        public static final String COMMAND = "COMMAND";
        public static final String DENIED_UNLINKED = "DENIED_UNLINKED";
        public static final String DENIED_SUSPENDED = "DENIED_SUSPENDED";
        public static final String SUSPEND = "SUSPEND";
        public static final String KICK = "KICK";
        public static final String SUSPEND_EDIT = "SUSPEND_EDIT";
        public static final String NOTE = "NOTE";
        public static final String LOG_EXPORT = "LOG_EXPORT";
        public static final String SPY_START = "SPY_START";
        public static final String SPY_END = "SPY_END";
        public static final String INVENTORY_VIEW = "INVENTORY_VIEW";
        public static final String DEATH = "DEATH";
        public static final String INVENTORY_SNAPSHOT = "INVENTORY_SNAPSHOT";
        public static final String INVENTORY_RESTORE = "INVENTORY_RESTORE";
        public static final String SETTINGS_CHANGE = "SETTINGS_CHANGE";
        /** An admin switched the website between the admin view and the community view. */
        public static final String DUTY = "DUTY";
        public static final String UNSUSPEND = "UNSUSPEND";
        public static final String WEB_LOGIN = "WEB_LOGIN";
        public static final String INTERACTION = "INTERACTION";
        public static final String ADVANCEMENT = "ADVANCEMENT";
        public static final String CREDITS_ADJUST = "CREDITS_ADJUST";
        /** A website Shop order: delivered, or declined and refunded. */
        public static final String SHOP_PURCHASE = "SHOP_PURCHASE";

        private EventTypes() {
        }
    }
}
