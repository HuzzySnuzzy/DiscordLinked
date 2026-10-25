package com.huzzysnuzzy.discordlinked;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loads a .properties file from the data directory, using the copy bundled in the jar for missing keys. */
final class ConfigFiles {

    private ConfigFiles() {
    }

    static Properties load(Path dataDirectory, String fileName) throws IOException {
        Properties defaults = new Properties();
        try (InputStream in = ConfigFiles.class.getResourceAsStream("/" + fileName)) {
            if (in == null) {
                throw new IOException("Default " + fileName + " missing from plugin jar");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(fileName);
        if (Files.notExists(file)) {
            try (InputStream in = ConfigFiles.class.getResourceAsStream("/" + fileName)) {
                Files.copy(in, file);
            }
        }

        // Keys added in newer versions fall back to the bundled defaults.
        Properties props = new Properties(defaults);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
