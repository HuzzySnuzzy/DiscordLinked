package com.huzzysnuzzy.discordlinked;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Settings loaded from {@code plugins/discordlinked/config.properties}. */
public record PluginConfig(
        String botToken,
        long guildId,
        long linkedRoleId,
        long logChannelId,
        long chatChannelId,
        boolean minecraftToDiscord,
        boolean discordToMinecraft,
        int codeExpiryMinutes,
        boolean logChat,
        boolean logCommands,
        boolean webEnabled,
        String webBindAddress,
        int webPort,
        String webPublicUrl,
        String oauthClientId,
        String oauthClientSecret,
        long adminRoleId,
        boolean dmPlayers,
        String googleSpreadsheetId,
        String googleServiceAccountFile,
        int spyMaxDistance,
        String webAccess,
        int snapshotsPerPlayer,
        long webOwnerId,
        int chatLogRetentionDays,
        int logRetentionDays,
        int snapshotRetentionDays,
        boolean fadEnabled,
        String creditsName,
        String creditsNamePlural,
        int creditsPlaytimeAmount,
        int creditsPlaytimeMinutes,
        int creditsAdvancementTask,
        int creditsAdvancementGoal,
        int creditsAdvancementChallenge,
        long fadChannelId,
        boolean fadDiscordInteractions,
        boolean fadDiscordAdvancements,
        boolean fadDiscordPing,
        boolean fadDiscordCommands,
        boolean webMemberAccess
) {

    public static PluginConfig load(Path dataDirectory, Logger logger) throws IOException {
        Properties props = ConfigFiles.load(dataDirectory, "config.properties", logger);
        return new PluginConfig(
                props.getProperty("bot-token").trim(),
                parseId(props, "guild-id"),
                parseId(props, "linked-role-id"),
                parseId(props, "log-channel-id"),
                parseId(props, "chat-channel-id"),
                parseBoolean(props, "minecraft-to-discord"),
                parseBoolean(props, "discord-to-minecraft"),
                Integer.parseInt(props.getProperty("code-expiry-minutes").trim()),
                parseBoolean(props, "log-chat"),
                parseBoolean(props, "log-commands"),
                parseBoolean(props, "web-enabled"),
                props.getProperty("web-bind-address").trim(),
                Integer.parseInt(props.getProperty("web-port").trim()),
                stripTrailingSlash(props.getProperty("web-public-url").trim()),
                props.getProperty("discord-client-id").trim(),
                props.getProperty("discord-client-secret").trim(),
                parseId(props, "admin-role-id"),
                parseBoolean(props, "dm-players"),
                props.getProperty("google-sheets-spreadsheet-id").trim(),
                props.getProperty("google-service-account-file").trim(),
                Integer.parseInt(props.getProperty("spy-max-distance").trim()),
                props.getProperty("web-access").trim().toLowerCase(),
                Integer.parseInt(props.getProperty("inventory-snapshots-per-player").trim()),
                parseId(props, "web-owner-id"),
                Integer.parseInt(props.getProperty("chat-log-retention-days").trim()),
                Integer.parseInt(props.getProperty("log-retention-days").trim()),
                Integer.parseInt(props.getProperty("inventory-snapshot-retention-days").trim()),
                parseBoolean(props, "fad-enabled"),
                nonBlank(props, "credits-name", "Credit"),
                nonBlank(props, "credits-name-plural", "Credits"),
                Integer.parseInt(props.getProperty("credits-playtime-amount").trim()),
                Integer.parseInt(props.getProperty("credits-playtime-minutes").trim()),
                Integer.parseInt(props.getProperty("credits-advancement-task").trim()),
                Integer.parseInt(props.getProperty("credits-advancement-goal").trim()),
                Integer.parseInt(props.getProperty("credits-advancement-challenge").trim()),
                parseId(props, "fad-channel-id"),
                parseBoolean(props, "fad-discord-interactions"),
                parseBoolean(props, "fad-discord-advancements"),
                parseBoolean(props, "fad-discord-ping"),
                parseBoolean(props, "fad-discord-commands"),
                parseBoolean(props, "web-member-access")
        );
    }

    private static long parseId(Properties props, String key) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value);
    }

    private static String nonBlank(Properties props, String key, String fallback) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean parseBoolean(Properties props, String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false").trim());
    }

    public boolean botConfigured() {
        return !botToken.isEmpty() && guildId != 0L;
    }

    /** "tunnel": automatic HTTPS through a Cloudflare quick tunnel. "direct": listen on web-port as-is. */
    public boolean webTunnel() {
        return !"direct".equals(webAccess);
    }

    /** Where interactions and advancements are posted: fad-channel-id, else the chat bridge channel. */
    public long fadChannel() {
        return fadChannelId != 0L ? fadChannelId : chatChannelId;
    }

    /** "1 Credit", "5 Credits", "0 Credits". */
    public String credits(long amount) {
        return amount + " " + currency(amount);
    }

    /** The credit name that fits the amount. */
    public String currency(long amount) {
        return Math.abs(amount) == 1 ? creditsName : creditsNamePlural;
    }

    public boolean chatBridgeEnabled() {
        return chatChannelId != 0L && (minecraftToDiscord || discordToMinecraft);
    }
}
