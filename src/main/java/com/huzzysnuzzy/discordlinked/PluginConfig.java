package com.huzzysnuzzy.discordlinked;

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
        boolean logCommands
) {

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Properties props = ConfigFiles.load(dataDirectory, "config.properties");
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
                parseBoolean(props, "log-commands")
        );
    }

    private static long parseId(Properties props, String key) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value);
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
