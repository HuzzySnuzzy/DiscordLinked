package com.huzzysnuzzy.discordlinked.spy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Inventory snapshots waiting to reach the proxy. Plugin messages can only travel through a connected
 * player, so a snapshot taken as someone quits (or while nobody else is online) waits here, on disk,
 * until any player with the channel is online. Files are deleted once the proxy confirms (ACK), and
 * anything unconfirmed is sent again.
 */
final class SnapshotOutbox {

    private static final long RESEND_AFTER_MS = 30_000;
    private static final int MAX_PER_FLUSH = 20;

    private final Plugin plugin;
    private final Path directory;
    private final Logger logger;
    private final Map<String, Long> sentAt = new ConcurrentHashMap<>();

    SnapshotOutbox(Plugin plugin, Path directory, Logger logger) throws IOException {
        this.plugin = plugin;
        this.directory = directory;
        this.logger = logger;
        Files.createDirectories(directory);
    }

    void add(String id, byte[] message) {
        try {
            Path temp = directory.resolve(id + ".tmp");
            Files.write(temp, message);
            Files.move(temp, directory.resolve(id + ".bin"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("Could not save an inventory snapshot to the outbox: " + e.getMessage());
        }
    }

    void acknowledge(String id) {
        sentAt.remove(id);
        try {
            Files.deleteIfExists(directory.resolve(id + ".bin"));
        } catch (IOException e) {
            logger.warning("Could not remove a delivered snapshot: " + e.getMessage());
        }
    }

    /** Sends pending snapshots through any online player. Safe to call from any thread. */
    void flush() {
        Player carrier = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getListeningPluginChannels().contains(DiscordLinkedSpy.INVENTORY_CHANNEL)) {
                carrier = player;
                break;
            }
        }
        if (carrier == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int sent = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.bin")) {
            for (Path file : files) {
                String id = file.getFileName().toString().replace(".bin", "");
                Long last = sentAt.get(id);
                if (last != null && now - last < RESEND_AFTER_MS) {
                    continue;
                }
                byte[] message = Files.readAllBytes(file);
                sentAt.put(id, now);
                Player via = carrier;
                via.getScheduler().run(plugin, task -> via.sendPluginMessage(plugin, DiscordLinkedSpy.INVENTORY_CHANNEL, message), null);
                if (++sent >= MAX_PER_FLUSH) {
                    break;
                }
            }
        } catch (IOException e) {
            logger.warning("Could not read the snapshot outbox: " + e.getMessage());
        }
    }
}
