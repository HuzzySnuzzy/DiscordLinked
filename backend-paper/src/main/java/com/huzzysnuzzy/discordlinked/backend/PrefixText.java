package com.huzzysnuzzy.discordlinked.backend;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bought prefixes as written on the website: &amp;-codes (&amp;6, &amp;#ff8800, &amp;l...) and/or MiniMessage
 * (&lt;gold&gt;, &lt;#ff8800&gt;, &lt;gradient:a:b&gt;, &lt;rainbow&gt;, &lt;b&gt;...). Only tags that colour and style
 * text are read; anything else is shown as written.
 */
final class PrefixText {

    private static final Pattern CODE = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");
    private static final Map<Character, String> COLORS = Map.ofEntries(Map.entry('0', "black"), Map.entry('1', "dark_blue"),
            Map.entry('2', "dark_green"), Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"), Map.entry('9', "blue"),
            Map.entry('a', "green"), Map.entry('b', "aqua"), Map.entry('c', "red"), Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"), Map.entry('f', "white"));
    private static final Map<Character, String> FORMATS = Map.of('l', "bold", 'o', "italic", 'n', "underlined",
            'm', "strikethrough", 'k', "obfuscated");
    private static final MiniMessage MINI = MiniMessage.builder().tags(TagResolver.resolver(StandardTags.color(),
            StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition(),
            StandardTags.reset())).build();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&')
            .hexColors().build();

    private PrefixText() {
    }

    /** &amp;-codes as MiniMessage tags; a colour code resets bold and the like first, like in game. */
    static String toMiniMessage(String text) {
        Matcher m = CODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String tag;
            if (code.startsWith("#")) {
                tag = "<reset><" + code + ">";
            } else if (code.equals("r")) {
                tag = "<reset>";
            } else if (COLORS.containsKey(code.charAt(0))) {
                tag = "<reset><" + COLORS.get(code.charAt(0)) + ">";
            } else {
                tag = "<" + FORMATS.get(code.charAt(0)) + ">";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Component render(String text) {
        return MINI.deserialize(toMiniMessage(text));
    }

    /**
     * What LuckPerms gets, for chat plugins: as written ("as-is"), plain &amp;-codes with &amp;#rrggbb ("legacy": a
     * gradient becomes a colour per letter), or MiniMessage ("minimessage").
     */
    static String forLuckPerms(String text, String format) {
        return switch (format) {
            case "legacy" -> LEGACY.serialize(render(text));
            case "minimessage" -> MiniMessage.miniMessage().serialize(render(text));
            default -> text;
        };
    }
}
