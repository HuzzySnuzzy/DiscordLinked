package com.huzzysnuzzy.discordlinked.fad;

import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interactions the Minecraft servers offer, as last sent by DiscordLinkedFAD (from its config.yml). Saved to
 * {@code fad-actions.json} so Discord's slash commands work right after a proxy restart. Also turns an
 * interaction line into chat text for Minecraft and Discord.
 */
public final class FadActions {

    /** {@code target} is "required", "optional" or "none". */
    public record Action(String name, String description, String target, List<String> messages,
                         List<String> soloMessages) {

        public boolean takesTarget() {
            return !"none".equals(target);
        }

        public boolean requiresTarget() {
            return "required".equals(target);
        }

        /** The lines to pick from, falling back to the other list when this case has none. */
        public List<String> lines(boolean withTarget) {
            List<String> preferred = withTarget ? messages : soloMessages;
            return preferred.isEmpty() ? (withTarget ? soloMessages : messages) : preferred;
        }
    }

    /** Everything one server sent. {@code actions} is by name, in config order. */
    public record Definitions(String version, String format, String nameFormat, boolean allowSelf, int cooldownSeconds,
                              Map<String, Action> actions) {
    }

    private static final String ACTOR = "";
    private static final String TARGET = "";

    private final Path file;
    private final Logger logger;
    private volatile Definitions current;
    private volatile String currentJson = "";

    public FadActions(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve("fad-actions.json");
        this.logger = logger;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                current = parse(json);
                currentJson = json;
            } catch (IOException | RuntimeException e) {
                logger.warn("Couldn't read {}; it will be sent again when a player joins a server with "
                        + "DiscordLinkedFAD: {}", file.getFileName(), e.getMessage());
            }
        }
    }

    /** The current interactions, or null if no server with DiscordLinkedFAD has connected yet. */
    public Definitions current() {
        return current;
    }

    /** Stores what a server sent. Returns true if it differs from before (so Discord's commands need updating). */
    public synchronized boolean update(String json) {
        Definitions parsed = parse(json);
        if (json.equals(currentJson)) {
            return false;
        }
        current = parsed;
        currentJson = json;
        try {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warn("Couldn't save {}: {}", file.getFileName(), e.getMessage());
        }
        return true;
    }

    private static Definitions parse(String json) {
        try {
            DataObject root = DataObject.fromJson(json);
            Map<String, Action> actions = new LinkedHashMap<>();
            DataArray list = root.getArray("actions");
            for (int i = 0; i < list.length(); i++) {
                DataObject a = list.getObject(i);
                Action action = new Action(a.getString("name"), a.getString("description", a.getString("name")),
                        a.getString("target", "required"), strings(a.optArray("messages").orElse(DataArray.empty())),
                        strings(a.optArray("soloMessages").orElse(DataArray.empty())));
                actions.put(action.name(), action);
            }
            return new Definitions(root.getString("version", "?"), root.getString("format", "<message>"),
                    root.getString("nameFormat", "<name>"), root.getBoolean("allowSelf", false),
                    root.getInt("cooldownSeconds", 5), Collections.unmodifiableMap(actions));
        } catch (ParsingException e) {
            throw new IllegalArgumentException("unreadable interactions list: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(DataArray array) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return List.copyOf(values);
    }

    // ---- Turning a line into chat ----

    /**
     * The line as shown in Minecraft: {@code format} around it, names in {@code nameFormat}. The line and formats
     * come from the server's config.yml (MiniMessage); player names are inserted as plain text.
     */
    public static Component minecraft(String format, String nameFormat, String line, String actor, String target) {
        MiniMessage mini = MiniMessage.miniMessage();
        Component message = mini.deserialize(line,
                Placeholder.component("actor", mini.deserialize(nameFormat, Placeholder.unparsed("name", actor))),
                Placeholder.component("target", mini.deserialize(nameFormat,
                        Placeholder.unparsed("name", target == null ? "" : target))));
        return mini.deserialize(format, Placeholder.component("message", message));
    }

    /** The line as plain text with names, for the logs and the website. */
    public static String plain(String line, String actor, String target) {
        Component message = MiniMessage.miniMessage().deserialize(line,
                Placeholder.unparsed("actor", actor), Placeholder.unparsed("target", target == null ? "" : target));
        return PlainTextComponentSerializer.plainText().serialize(message);
    }

    /**
     * The line for Discord: formatting tags removed, markdown escaped, and {@code <actor>}/{@code <target>}
     * replaced by the given text (an @mention, or a bold name for members who aren't linked).
     */
    public static String discord(String line, String actor, String target) {
        String marked = line.replace("<actor>", ACTOR).replace("<target>", TARGET);
        String text = MarkdownSanitizer.escape(MiniMessage.miniMessage().stripTags(marked));
        return text.replace(ACTOR, actor).replace(TARGET, target == null ? "" : target);
    }

    /** How a name shows on Discord: an @mention when linked, otherwise in bold. */
    public static String discordName(Long discordId, String name) {
        return discordId != null ? "<@" + discordId + ">" : "**" + MarkdownSanitizer.escape(name) + "**";
    }
}
