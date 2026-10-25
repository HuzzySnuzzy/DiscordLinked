package com.huzzysnuzzy.discordlinked;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads a .properties file from the data directory. Settings added in newer versions are appended
 * to the user's file (with their comments) so they show up without deleting the file.
 */
final class ConfigFiles {

    private ConfigFiles() {
    }

    static Properties load(Path dataDirectory, String fileName, Logger logger) throws IOException {
        String bundled = readBundled(fileName);
        Properties defaults = new Properties();
        defaults.load(new StringReader(bundled));

        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(fileName);
        if (Files.notExists(file)) {
            Files.writeString(file, bundled, StandardCharsets.UTF_8);
        }

        Properties userOnly = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            userOnly.load(reader);
        }
        appendMissing(file, bundled, userOnly, logger);

        Properties props = new Properties(defaults);
        props.putAll(userOnly);
        return props;
    }

    /** Copies each bundled entry the user's file lacks, together with the comment block above it. */
    private static void appendMissing(Path file, String bundled, Properties existing, Logger logger) throws IOException {
        StringBuilder additions = new StringBuilder();
        List<String> added = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        String[] lines = bundled.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                pendingComments.clear();
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                pendingComments.add(lines[i]);
                continue;
            }
            StringBuilder entry = new StringBuilder(lines[i]);
            while (lines[i].endsWith("\\") && i + 1 < lines.length) {
                entry.append('\n').append(lines[++i]);
            }
            Properties single = new Properties();
            single.load(new StringReader(entry.toString()));
            for (String key : single.stringPropertyNames()) {
                if (!existing.containsKey(key)) {
                    additions.append('\n');
                    pendingComments.forEach(c -> additions.append(c).append('\n'));
                    additions.append(entry).append('\n');
                    added.add(key);
                }
            }
            pendingComments.clear();
        }
        if (!added.isEmpty()) {
            Files.writeString(file, "\n# ---- Added by a DiscordLinked update ----\n" + additions,
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            logger.info("Added new settings to {}: {}", file.getFileName(), String.join(", ", added));
        }
    }

    private static String readBundled(String fileName) throws IOException {
        try (InputStream in = ConfigFiles.class.getResourceAsStream("/" + fileName)) {
            if (in == null) {
                throw new IOException("Default " + fileName + " missing from plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
