package com.huzzysnuzzy.discordlinked;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings loaded from {@code plugins/discordlinked/config.properties}.
 * A commented default file is copied there on first start.
 */
public record PluginConfig(
        String botToken,
        long guildId,
        long linkedRoleId,
        long logChannelId,
        int codeExpiryMinutes,
        boolean logChat,
        boolean logCommands,
        String kickMessage,
        String unlinkedKickMessage
) {

    private static final String FILE_NAME = "config.properties";

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(FILE_NAME);
        if (Files.notExists(file)) {
            try (InputStream in = PluginConfig.class.getResourceAsStream("/" + FILE_NAME)) {
                if (in == null) {
                    throw new IOException("Default " + FILE_NAME + " missing from plugin jar");
                }
                Files.copy(in, file);
            }
        }

        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        return new PluginConfig(
                props.getProperty("bot-token", "").trim(),
                parseId(props, "guild-id"),
                parseId(props, "linked-role-id"),
                parseId(props, "log-channel-id"),
                Integer.parseInt(props.getProperty("code-expiry-minutes", "10").trim()),
                Boolean.parseBoolean(props.getProperty("log-chat", "true").trim()),
                Boolean.parseBoolean(props.getProperty("log-commands", "true").trim()),
                props.getProperty("kick-message",
                        "<red>You must link your Discord account to join.</red>\n\n"
                                + "Run <yellow>/link <code></yellow> in our Discord server."),
                props.getProperty("unlinked-kick-message",
                        "<red>Your Discord account was unlinked.</red>")
        );
    }

    private static long parseId(Properties props, String key) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value);
    }

    public boolean botConfigured() {
        return !botToken.isEmpty() && guildId != 0L;
    }
}
