package com.huzzysnuzzy.discordlinked.fad;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml, read once per (re)load. Settings and messages missing from the server's file fall back to the
 * built-in defaults, but the actions list is taken exactly as written so removed interactions stay removed.
 */
final class FadSettings {

    /** A configurable command name, e.g. credits-command. */
    record CommandName(String name, List<String> aliases) {
    }

    private static final String NAME_PATTERN = "[a-z0-9_-]{1,32}";

    final String format;
    final String nameFormat;
    final int cooldownSeconds;
    final boolean allowSelf;
    final int afkMinutes;
    final boolean advancementCredits;
    final CommandName creditsCommand;
    final CommandName interactionsCommand;
    final CommandName sellCommand;
    final CommandName sellHandCommand;
    final CommandName worthCommand;
    final CommandName microTopCommand;
    final CommandName exchangeCommand;
    /** By command name, in config order. */
    final Map<String, Action> actions;
    private final YamlConfiguration user;
    private final YamlConfiguration bundled;

    private FadSettings(YamlConfiguration user, YamlConfiguration bundled, Logger logger) {
        this.user = user;
        this.bundled = bundled;
        format = string("format");
        nameFormat = string("name-format");
        cooldownSeconds = Math.max(0, integer("cooldown-seconds"));
        allowSelf = bool("allow-self");
        afkMinutes = Math.max(0, integer("afk-minutes"));
        advancementCredits = bool("advancement-credits");
        creditsCommand = commandName("credits-command", logger);
        interactionsCommand = commandName("interactions-command", logger);
        sellCommand = commandName("sell-command", logger);
        sellHandCommand = commandName("sellhand-command", logger);
        worthCommand = commandName("worth-command", logger);
        microTopCommand = commandName("microcredit-top-command", logger);
        exchangeCommand = commandName("exchange-command", logger);
        ConfigurationSection section = user.isConfigurationSection("actions")
                ? user.getConfigurationSection("actions") : bundled.getConfigurationSection("actions");
        actions = Collections.unmodifiableMap(readActions(section, logger));
    }

    static FadSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        YamlConfiguration bundled = new YamlConfiguration();
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                throw new IllegalStateException("config.yml missing from the plugin jar");
            }
            bundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Built-in config.yml is unreadable", e);
        }
        YamlConfiguration user = new YamlConfiguration();
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("config.yml couldn't be read, so the built-in defaults are used until it's fixed: "
                    + e.getMessage());
            user = bundled;
        }
        return new FadSettings(user, bundled, plugin.getLogger());
    }

    // ---- Messages ----

    Component message(String key, TagResolver... placeholders) {
        return MiniMessage.miniMessage().deserialize(string("messages." + key), placeholders);
    }

    // ---- What the proxy needs to post on Discord and run the same interactions as slash commands ----

    String toJson(String version) {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("format", format);
        root.addProperty("nameFormat", nameFormat);
        root.addProperty("allowSelf", allowSelf);
        root.addProperty("cooldownSeconds", cooldownSeconds);
        JsonArray list = new JsonArray();
        for (Action action : actions.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", action.name());
            entry.addProperty("description", action.description());
            entry.addProperty("target", action.target().name().toLowerCase(Locale.ROOT));
            entry.add("messages", array(action.messages()));
            entry.add("soloMessages", array(action.soloMessages()));
            list.add(entry);
        }
        root.add("actions", list);
        return root.toString();
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ---- Parsing ----

    private static Map<String, Action> readActions(ConfigurationSection section, Logger logger) {
        Map<String, Action> result = new LinkedHashMap<>();
        if (section == null) {
            logger.warning("config.yml has no actions, so there are no interaction commands.");
            return result;
        }
        for (String key : section.getKeys(false)) {
            String name = key.toLowerCase(Locale.ROOT);
            ConfigurationSection a = section.getConfigurationSection(key);
            if (!name.matches(NAME_PATTERN) || a == null) {
                logger.warning("Skipping action '" + key + "': names may only use a-z, 0-9, _ and -, up to 32 characters.");
                continue;
            }
            Action.Target target;
            try {
                target = Action.Target.valueOf(a.getString("target", "required").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping action '" + name + "': target must be required, optional or none.");
                continue;
            }
            List<String> messages = a.getStringList("messages");
            List<String> solo = a.getStringList("solo-messages");
            boolean usable = switch (target) {
                case REQUIRED -> !messages.isEmpty();
                case OPTIONAL -> !messages.isEmpty() || !solo.isEmpty();
                case NONE -> !solo.isEmpty();
            };
            if (!usable) {
                logger.warning("Skipping action '" + name + "': it has no " + (target == Action.Target.NONE
                        ? "solo-messages" : "messages") + ".");
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (String alias : a.getStringList("aliases")) {
                String clean = alias.trim().toLowerCase(Locale.ROOT);
                if (clean.matches(NAME_PATTERN) && !clean.equals(name)) {
                    aliases.add(clean);
                }
            }
            String sound = a.getString("sound", "").trim();
            String description = a.getString("description", "").trim();
            result.put(name, new Action(name,
                    description.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : description,
                    List.copyOf(aliases), target, List.copyOf(messages), List.copyOf(solo),
                    sound.isEmpty() ? null : sound.toLowerCase(Locale.ROOT),
                    particle(a.getString("particle", ""), name, logger), a.getBoolean("leap", false)));
        }
        return result;
    }

    /** Looks particles up by their Minecraft key, which stays the same across versions. */
    private static Particle particle(String key, String action, Logger logger) {
        if (key == null || key.isBlank()) {
            return null;
        }
        NamespacedKey namespaced = NamespacedKey.fromString(key.trim().toLowerCase(Locale.ROOT));
        Particle particle = namespaced == null ? null : Registry.PARTICLE_TYPE.get(namespaced);
        if (particle == null) {
            logger.warning("Action '" + action + "': unknown particle '" + key + "', so it has none.");
            return null;
        }
        if (particle.getDataType() != Void.class) {
            logger.warning("Action '" + action + "': particle '" + key + "' needs extra data (like a colour), "
                    + "which isn't supported, so it has none.");
            return null;
        }
        return particle;
    }

    private CommandName commandName(String path, Logger logger) {
        String name = string(path + ".name").trim().toLowerCase(Locale.ROOT);
        if (!name.matches(NAME_PATTERN)) {
            logger.warning(path + ".name '" + name + "' isn't a valid command name; using the default.");
            name = bundled.getString(path + ".name", "");
        }
        List<String> aliases = new ArrayList<>();
        for (String alias : user.isSet(path + ".aliases") ? user.getStringList(path + ".aliases")
                : bundled.getStringList(path + ".aliases")) {
            String clean = alias.trim().toLowerCase(Locale.ROOT);
            if (clean.matches(NAME_PATTERN) && !clean.equals(name)) {
                aliases.add(clean);
            }
        }
        return new CommandName(name, List.copyOf(aliases));
    }

    private String string(String path) {
        return user.isSet(path) ? user.getString(path, "") : bundled.getString(path, "");
    }

    private int integer(String path) {
        return user.isSet(path) ? user.getInt(path) : bundled.getInt(path);
    }

    private boolean bool(String path) {
        return user.isSet(path) ? user.getBoolean(path) : bundled.getBoolean(path);
    }
}
