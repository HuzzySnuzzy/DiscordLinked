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

    private Migration() {
    }

    /** Runs before anything is loaded. {@code folder} is this plugin's own data folder. */
    static void run(Path folder, Logger logger) {
        Path fad = folder.resolveSibling("DiscordLinkedFAD");
        Path spyFolder = folder.resolveSibling("DiscordLinkedSpy");
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
        }
        // Only before the outbox exists here: snapshots sent from it are deleted once the proxy has them, so
        // copying the old ones again on a later start would send them twice.
        Path oldOutbox = spyFolder.resolve("outbox");
        if (!Files.exists(folder.resolve("outbox")) && Files.isDirectory(oldOutbox)) {
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
