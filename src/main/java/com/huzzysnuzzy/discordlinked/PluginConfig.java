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
        boolean webMemberAccess,
        boolean shopEnabled,
        int shopPricePercent,
        int dailyRewardBase,
        int dailyRewardPerDay,
        int dailyRewardMax,
        int streakAnnounceDays,
        String webDomain,
        String webDomainPrefix,
        String webTunnelToken,
        boolean marketEnabled,
        int sellPricePercent,
        int microPerCredit,
        String microName,
        String microNamePlural,
        String microSymbol,
        String microLeaderboard,
        boolean sellPriceDrop,
        int sellPriceDropAfter,
        int sellPriceRecoveryHours,
        boolean marketWeeklyPost,
        java.util.List<Long> commandChannelIds
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
                parseBoolean(props, "web-member-access"),
                parseBoolean(props, "shop-enabled"),
                Integer.parseInt(props.getProperty("shop-price-percent").trim()),
                Integer.parseInt(props.getProperty("daily-reward-base").trim()),
                Integer.parseInt(props.getProperty("daily-reward-per-day").trim()),
                Integer.parseInt(props.getProperty("daily-reward-max").trim()),
                Integer.parseInt(props.getProperty("streak-announce-days").trim()),
                props.getProperty("web-domain").trim().toLowerCase(),
                props.getProperty("web-domain-prefix").trim().toLowerCase(),
                props.getProperty("web-tunnel-token").trim(),
                parseBoolean(props, "market-enabled"),
                Integer.parseInt(props.getProperty("sell-price-percent").trim()),
                Math.max(1, Integer.parseInt(props.getProperty("microcredits-per-credit").trim())),
                nonBlank(props, "microcredits-name", "Microcredit"),
                nonBlank(props, "microcredits-name-plural", "Microcredits"),
                nonBlank(props, "microcredits-symbol", "µc"),
                "earned".equalsIgnoreCase(props.getProperty("microcredits-leaderboard").trim()) ? "earned" : "balance",
                parseBoolean(props, "sell-price-drop"),
                Math.max(1, Integer.parseInt(props.getProperty("sell-price-drop-after").trim())),
                Math.max(1, Integer.parseInt(props.getProperty("sell-price-recovery-hours").trim())),
                parseBoolean(props, "market-weekly-post"),
                parseIds(props, "command-channel-ids")
        );
    }

    private static long parseId(Properties props, String key) {
        String value = props.getProperty(key, "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value);
    }

    /** "123, 456" -> [123, 456]; anything that isn't an ID is skipped. */
    private static java.util.List<Long> parseIds(Properties props, String key) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String part : props.getProperty(key, "").split("[,\\s]+")) {
            if (part.matches("\\d{15,21}")) {
                ids.add(Long.parseLong(part));
            }
        }
        return java.util.List.copyOf(ids);
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

    /** The website's own address (e.g. dla.example.com), or "" if no domain is set. */
    public String webHostname() {
        if (webDomain.isEmpty()) {
            return "";
        }
        return webDomainPrefix.isEmpty() ? webDomain : webDomainPrefix + "." + webDomain;
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

    /** "1,234 µc". */
    public String micro(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount) + " " + microSymbol;
    }

    /** Selling items for microcredits: needs the community features on too. */
    public boolean marketOpen() {
        return fadEnabled && marketEnabled;
    }

    /** Where slash commands may be used: command-channel-ids, else the chat channel; empty = anywhere. */
    public java.util.List<Long> commandChannels() {
        if (!commandChannelIds.isEmpty()) {
            return commandChannelIds;
        }
        return chatChannelId != 0L ? java.util.List.of(chatChannelId) : java.util.List.of();
    }

    public boolean chatBridgeEnabled() {
        return chatChannelId != 0L && (minecraftToDiscord || discordToMinecraft);
    }
}
