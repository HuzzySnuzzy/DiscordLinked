package com.huzzysnuzzy.discordlinked;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The settings the website's Settings page can change, with their validation. Secrets (bot token, client
 * secret) are deliberately not here: they can only be changed in config.properties.
 */
public final class SettingsEditor {

    public enum Type { BOOLEAN, NUMBER, DISCORD_ID, TEXT, URL, CHOICE, NAME }

    /** {@code restart}: the new value only takes effect after the proxy restarts. */
    public record Field(String key, String group, String label, String help, Type type, boolean restart,
                        long min, long max, List<String> choices) {
    }

    private static Field bool(String key, String group, String label, String help, boolean restart) {
        return new Field(key, group, label, help, Type.BOOLEAN, restart, 0, 0, List.of());
    }

    private static Field number(String key, String group, String label, String help, long min, long max) {
        return new Field(key, group, label, help, Type.NUMBER, false, min, max, List.of());
    }

    private static Field name(String key, String group, String label, String help) {
        return new Field(key, group, label, help, Type.NAME, false, 1, 32, List.of());
    }

    private static Field id(String key, String group, String label, String help, boolean restart) {
        return new Field(key, group, label, help, Type.DISCORD_ID, restart, 0, 0, List.of());
    }

    public static final List<Field> FIELDS = List.of(
            number("code-expiry-minutes", "Linking", "Link code lifetime",
                    "Minutes a link code from the kick screen stays valid.", 1, 1440),
            id("linked-role-id", "Linking", "Linked role",
                    "Role given to members once linked. Empty = no role. The bot's role must be above it.", false),

            id("guild-id", "Discord", "Discord server", "The server the bot works in.", true),
            id("admin-role-id", "Discord", "Admin role",
                    "Members with this role can use the admin website and /dla.", false),
            id("log-channel-id", "Discord", "Log channel",
                    "Where links, suspensions, kicks, restores and joins/leaves are posted. Empty = off.", false),
            bool("dm-players", "Discord", "Message players on Discord",
                    "DM players when they're suspended, kicked, unsuspended or unlinked by staff.", false),

            id("chat-channel-id", "Chat bridge", "Chat channel", "Channel mirrored to and from Minecraft chat.", false),
            bool("minecraft-to-discord", "Chat bridge", "Minecraft → Discord",
                    "Send in-game chat, joins and leaves to the chat channel.", false),
            bool("discord-to-minecraft", "Chat bridge", "Discord → Minecraft",
                    "Show messages from the chat channel in game. Needs Message Content Intent on the bot.", true),

            bool("log-chat", "Logging & data", "Log chat", "Store chat messages in the logs.", false),
            bool("log-commands", "Logging & data", "Log commands", "Store commands players run in the logs.", false),
            number("chat-log-retention-days", "Logging & data", "Keep chat & command logs for",
                    "Days. Older chat, command and interaction entries are deleted. 0 = forever.", 0, 3650),
            number("log-retention-days", "Logging & data", "Keep other logs for",
                    "Days. Joins, IPs, admin actions and everything else. 0 = forever.", 0, 3650),

            number("inventory-snapshots-per-player", "Inventory snapshots", "Snapshots to keep per player",
                    "Older snapshots beyond this number are deleted.", 1, 1000),
            number("inventory-snapshot-retention-days", "Inventory snapshots", "Delete snapshots older than",
                    "Days. 0 = only the per-player limit applies.", 0, 3650),

            number("spy-max-distance", "Spy", "Maximum spy distance",
                    "Blocks the admin may get from the player before the session ends.", 16, 100_000),

            bool("fad-enabled", "Community & credits", "Community features",
                    "Interactions (/hug, /kiss...) and credits. Needs DiscordLinkedFAD on the Minecraft servers.", false),
            name("credits-name", "Community & credits", "Credit name (one)",
                    "What one credit is called, e.g. \"1 Pawbuck\"."),
            name("credits-name-plural", "Community & credits", "Credit name (several)",
                    "What more than one are called, e.g. \"5 Pawbucks\"."),
            number("credits-playtime-amount", "Community & credits", "Credits for playing",
                    "Given for every block of active (not AFK) play below. 0 = none.", 0, 1_000_000),
            number("credits-playtime-minutes", "Community & credits", "Every … minutes of active play",
                    "How long a block of active play is.", 1, 1440),
            number("credits-advancement-task", "Community & credits", "Credits per advancement",
                    "Most advancements (task). Only the first time a player makes it. 0 = none.", 0, 1_000_000),
            number("credits-advancement-goal", "Community & credits", "Credits per goal advancement",
                    "The rounded-frame ones. 0 = none.", 0, 1_000_000),
            number("credits-advancement-challenge", "Community & credits", "Credits per challenge advancement",
                    "The spiky purple-frame ones. 0 = none.", 0, 1_000_000),
            id("fad-channel-id", "Community & credits", "Interactions channel",
                    "Discord channel for interactions and advancements. Empty = the chat channel.", false),
            bool("fad-discord-interactions", "Community & credits", "Post interactions to Discord",
                    "Shows /hug, /kiss and friends in the channel, with @mentions of linked members.", false),
            bool("fad-discord-advancements", "Community & credits", "Post advancements to Discord",
                    "Announces advancements in the channel.", false),
            bool("fad-discord-ping", "Community & credits", "Ping who was picked",
                    "Off = the @mention shows but doesn't send them a notification.", false),
            bool("fad-discord-commands", "Community & credits", "Interactions as slash commands",
                    "Lets Discord members use /hug @someone and friends; they show in game too.", false),

            bool("web-enabled", "Website", "Website enabled",
                    "Turning this off also turns off this page; re-enable it in config.properties.", true),
            new Field("web-access", "Website", "How the website is reached",
                    "tunnel = automatic https:// address (changes on restart). direct = plain http on the port below.",
                    Type.CHOICE, true, 0, 0, List.of("tunnel", "direct")),
            new Field("web-port", "Website", "Port", "Local port the website listens on.", Type.NUMBER, true, 1, 65535, List.of()),
            new Field("web-bind-address", "Website", "Listen address (direct mode)",
                    "0.0.0.0 = reachable from anywhere, 127.0.0.1 = only this machine.", Type.TEXT, true, 0, 0, List.of()),
            new Field("web-public-url", "Website", "Public address (direct mode)",
                    "e.g. https://panel.example.com. Empty = http://<public IP>:<port>.", Type.URL, true, 0, 0, List.of()),
            id("web-owner-id", "Website", "Settings owner",
                    "The only Discord user who can open this page. Empty = the Discord server owner. "
                            + "Changing it hands this page to someone else immediately.", false),

            new Field("google-sheets-spreadsheet-id", "Google Sheets", "Spreadsheet ID",
                    "From the sheet's URL. Empty = Google Sheets export off.", Type.TEXT, true, 0, 0, List.of()),
            new Field("google-service-account-file", "Google Sheets", "Service account key file",
                    "File name inside plugins/discordlinked/.", Type.TEXT, true, 0, 0, List.of()));

    private final DiscordLinked plugin;

    public SettingsEditor(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    /** Current values as written in config.properties (bundled defaults for missing keys). */
    public Map<String, String> values() throws IOException {
        Properties props = ConfigFiles.load(plugin.dataDirectory(), "config.properties", plugin.logger());
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : FIELDS) {
            values.put(field.key(), props.getProperty(field.key(), "").trim());
        }
        return values;
    }

    /** Whether each secret is filled in, without ever revealing it. */
    public Map<String, Boolean> secrets() throws IOException {
        Properties props = ConfigFiles.load(plugin.dataDirectory(), "config.properties", plugin.logger());
        Map<String, Boolean> secrets = new LinkedHashMap<>();
        secrets.put("botToken", !props.getProperty("bot-token", "").isBlank());
        secrets.put("clientSecret", !props.getProperty("discord-client-secret", "").isBlank());
        String keyFile = props.getProperty("google-service-account-file", "").trim();
        secrets.put("googleKey", !keyFile.isEmpty() && Files.exists(plugin.dataDirectory().resolve(keyFile)));
        return secrets;
    }

    /** What was saved. */
    public record Result(List<String> changed, List<String> needRestart) {
    }

    /** Validates everything first; nothing is written if any value is invalid. */
    public Result apply(Map<String, String> requested, String actor) throws IOException {
        Map<String, String> current = values();
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            Field field = FIELDS.stream().filter(f -> f.key().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + entry.getKey()));
            String value = validate(field, entry.getValue() == null ? "" : entry.getValue().trim());
            if (!value.equals(current.get(field.key()))) {
                changes.put(field.key(), value);
            }
        }
        if (changes.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        ConfigFiles.update(plugin.dataDirectory(), "config.properties", changes);
        plugin.reloadConfig();

        List<String> changed = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Field field = FIELDS.stream().filter(f -> f.key().equals(change.getKey())).findFirst().orElseThrow();
            changed.add(field.label());
            if (field.restart()) {
                restart.add(field.label());
            }
            plugin.audit().log(null, null, null, EventTypes.SETTINGS_CHANGE, field.key() + ": "
                    + show(current.get(field.key())) + " -> " + show(change.getValue()), actor);
        }
        return new Result(changed, restart);
    }

    private static String show(String value) {
        return value == null || value.isEmpty() ? "(empty)" : value;
    }

    private static String validate(Field field, String value) {
        String label = field.label();
        if (value.contains("\n") || value.contains("\r") || value.length() > 300) {
            throw new IllegalArgumentException(label + ": must be a single line under 300 characters");
        }
        switch (field.type()) {
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException(label + ": must be on or off");
                }
            }
            case NUMBER -> {
                long number;
                try {
                    number = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + ": must be a whole number");
                }
                if (number < field.min() || number > field.max()) {
                    throw new IllegalArgumentException(label + ": must be between " + field.min() + " and " + field.max());
                }
                return Long.toString(number);
            }
            case DISCORD_ID -> {
                if (!value.isEmpty() && !value.matches("\\d{15,21}")) {
                    throw new IllegalArgumentException(label + ": must be a Discord ID (digits only) or empty");
                }
            }
            case URL -> {
                if (!value.isEmpty() && !value.matches("https?://[^\\s]+")) {
                    throw new IllegalArgumentException(label + ": must start with http:// or https://");
                }
                if (value.endsWith("/")) {
                    return value.substring(0, value.length() - 1);
                }
            }
            case CHOICE -> {
                if (!field.choices().contains(value)) {
                    throw new IllegalArgumentException(label + ": must be one of " + String.join(", ", field.choices()));
                }
            }
            case TEXT -> {
                // single line, checked above
            }
            case NAME -> {
                if (value.isEmpty() || value.length() > field.max() || value.matches(".*[<>{}\\\\].*")) {
                    throw new IllegalArgumentException(label + ": must be 1 to " + field.max()
                            + " characters, without < > { } or \\");
                }
            }
        }
        return value;
    }
}
