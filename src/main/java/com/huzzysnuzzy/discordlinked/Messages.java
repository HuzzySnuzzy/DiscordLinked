package com.huzzysnuzzy.discordlinked;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every player- and Discord-facing text, loaded from {@code plugins/discordlinked/messages.properties}.
 * Minecraft messages are MiniMessage with {@code <placeholder>} tags; Discord messages use {@code {placeholder}}.
 */
public final class Messages {

    private static final Pattern DISCORD_PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private final Properties props;

    private Messages(Properties props) {
        this.props = props;
    }

    public static Messages load(Path dataDirectory) throws IOException {
        return new Messages(ConfigFiles.load(dataDirectory, "messages.properties"));
    }

    /**
     * A Minecraft message. Placeholders are given as name/value pairs and inserted as plain text,
     * so player input can't inject formatting.
     */
    public Component minecraft(String key, String... placeholders) {
        TagResolver.Builder resolver = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolver.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return MiniMessage.miniMessage().deserialize(raw(key), resolver.build());
    }

    /**
     * A Discord message with {@code {name}} placeholders replaced in a single pass,
     * so a value containing "{...}" is never expanded again. Unknown placeholders are left as-is.
     */
    public String discord(String key, String... placeholders) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.put(placeholders[i], placeholders[i + 1]);
        }
        Matcher matcher = DISCORD_PLACEHOLDER.matcher(raw(key));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Messages left empty in the file are switched off. */
    public boolean isEmpty(String key) {
        return raw(key).isBlank();
    }

    private String raw(String key) {
        return props.getProperty(key, key);
    }
}
