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
        String googleServiceAccountFile
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
                props.getProperty("google-service-account-file").trim()
        );
    }

    private static long parseId(Properties props, String key) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value);
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

    public boolean chatBridgeEnabled() {
        return chatChannelId != 0L && (minecraftToDiscord || discordToMinecraft);
    }
}
