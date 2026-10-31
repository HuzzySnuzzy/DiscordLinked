package com.huzzysnuzzy.discordlinked.backend;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The first time DiscordLinkedBackend starts, copies what DiscordLinkedFAD and DiscordLinkedSpy left in their
 * folders next to it: DiscordLinkedFAD's config.yml (and anything else in its folder), and DiscordLinkedSpy's
 * snapshots that never reached the proxy and its logout spots. Nothing that's already here is replaced, and the old
 * folders are left as they are.
 */
final class Migration {

    /** DiscordLinkedFAD's default reload message, which a copied config.yml still has unless it was changed. */
    private static final String OLD_RELOADED =
            "\"<green>DiscordLinkedFAD reloaded with <count> interactions.</green>\"";
    private static final String NEW_RELOADED =
            "\"<green>DiscordLinkedBackend reloaded with <count> interactions.</green>\"";

    private Migration() {
    }

    /** Runs before anything is loaded. {@code folder} is this plugin's own data folder. */
    static void run(Path folder, Logger logger) {
        Path fad = folder.resolveSibling("DiscordLinkedFAD");
        Path spyFolder = folder.resolveSibling("DiscordLinkedSpy");
        // The outbox is made on every start, so without it this plugin has never run here. Only then is anything
        // copied: the old folders stay, so an owner who deletes config.yml for fresh defaults would get the old
        // one back, and snapshots the proxy already has would be sent twice.
        if (Files.exists(folder.resolve("outbox"))) {
            return;
        }
        List<String> copied = new ArrayList<>();
        if (!Files.exists(folder.resolve("config.yml")) && Files.isRegularFile(fad.resolve("config.yml"))) {
            try (Stream<Path> files = Files.walk(fad)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = fad.relativize(file).toString();
                    if (copy(file, folder.resolve(name), logger)) {
                        copied.add("DiscordLinkedFAD/" + name.replace('\\', '/'));
                    }
                }
            } catch (IOException e) {
                logger.warning("Couldn't read the old DiscordLinkedFAD folder: " + e.getMessage());
            }
            if (copied.contains("DiscordLinkedFAD/config.yml")) {
                renameReloaded(folder.resolve("config.yml"), logger);
            }
        }
        Path oldOutbox = spyFolder.resolve("outbox");
        if (Files.isDirectory(oldOutbox)) {
            int snapshots = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(oldOutbox, "*.bin")) {
                for (Path file : files) {
                    if (copy(file, folder.resolve("outbox").resolve(file.getFileName().toString()), logger)) {
                        snapshots++;
                    }
                }
            } catch (IOException e) {
                logger.warning("Couldn't read the old DiscordLinkedSpy outbox: " + e.getMessage());
            }
            if (snapshots > 0) {
                copied.add(snapshots + (snapshots == 1 ? " inventory snapshot" : " inventory snapshots")
                        + " still waiting in DiscordLinkedSpy/outbox");
            }
        }
        Path spots = spyFolder.resolve("logout-spots.properties");
        if (Files.isRegularFile(spots) && copy(spots, folder.resolve("logout-spots.properties"), logger)) {
            copied.add("DiscordLinkedSpy/logout-spots.properties");
        }
        if (!copied.isEmpty()) {
            logger.info("Copied from the old plugins' folders: " + String.join(", ", copied)
                    + ". The old folders are left as they are; delete them once everything works.");
        }
    }

    /** The copied config.yml's reload message names this plugin, unless the owner wrote their own. */
    private static void renameReloaded(Path config, Logger logger) {
        try {
            String text = Files.readString(config);
            String renamed = text.replace(OLD_RELOADED, NEW_RELOADED);
            if (!renamed.equals(text)) {
                Files.writeString(config, renamed);
            }
        } catch (IOException e) {
            logger.warning("Couldn't update the reload message in " + config + ": " + e.getMessage());
        }
    }

    /** Copies a file unless the target already exists. True if it was copied. */
    private static boolean copy(Path from, Path to, Logger logger) {
        if (Files.exists(to)) {
            return false;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
            return true;
        } catch (IOException e) {
            logger.warning("Couldn't copy " + from + " to " + to + ": " + e.getMessage());
            return false;
        }
    }
}
