package com.huzzysnuzzy.discordlinked.update;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.velocitypowered.api.proxy.Player;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Updates DiscordLinked itself (see {@link Updater}). A downloaded jar waits in {@code plugins/discordlinked/update/}
 * and is swapped in when the proxy shuts down: Velocity has no update folder, and a running plugin's jar can't be
 * replaced (its classes and website files are read from it). On shutdown the new jar is moved next to the old one
 * and the old one is moved to {@code update/previous/}; where the old one can't be moved (Windows keeps it locked
 * while the proxy runs) the new one goes back, and the website says to swap them by hand.
 *
 * <p>The proxy also tells the backend servers the update settings for them and the newest release it has seen
 * (UPDATES), so they don't each need a setting of their own and look sooner.
 */
public final class ProxyUpdater extends Updater {

    /** Points the updater at another jar than the one this class came from (tests). */
    public static final String JAR_PROPERTY = "discordlinked.update.jar";
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"discordlinked\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]{1,64})\"");
    private static final Pattern MAIN = Pattern.compile("\"main\"\\s*:\\s*\"([^\"]{1,200})\"");

    /** Written when the swap at shutdown failed, so the website can say so after the next start. */
    private static final String SWAP_PROBLEM = "install-failed.txt";

    private final DiscordLinked plugin;

    public ProxyUpdater(DiscordLinked plugin) {
        super("DiscordLinked/" + DiscordLinked.VERSION + " (updater; +https://github.com/"
                + ReleaseClient.DEFAULT_REPOSITORY + ")", "DiscordLinked updater");
        this.plugin = plugin;
    }

    /** The jar DiscordLinked runs from, or null if it doesn't run from one (e.g. in tests). */
    public static Path ownJar() {
        String override = System.getProperty(JAR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip());
        }
        try {
            var source = DiscordLinked.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    /** Why updates can't be installed here (empty if they can). */
    public String installProblem() {
        if (ownJar() == null) {
            return "DiscordLinked doesn't run from a jar file here, so it can only say when there's an update.";
        }
        Path file = workDirectory().resolve(SWAP_PROBLEM);
        try {
            if (Files.exists(file)) {
                if (stagedJar() == null) {
                    Files.deleteIfExists(file);
                    return "";
                }
                return Files.readString(file).strip();
            }
        } catch (IOException e) {
            // nothing to say then
        }
        return "";
    }

    @Override
    protected String currentVersion() {
        return DiscordLinked.VERSION;
    }

    @Override
    protected ReleaseClient.Asset asset(ReleaseClient.Release release) {
        return release.proxy();
    }

    @Override
    protected Path workDirectory() {
        return plugin.dataDirectory().resolve("update");
    }

    @Override
    protected String downloadProblem() {
        return ownJar() == null ? installProblem() : null;
    }

    @Override
    protected String restartProblem() {
        String problem = installProblem();
        return problem.isEmpty() ? null : problem;
    }

    @Override
    protected void verify(Path jar, String version) throws IOException {
        String descriptor = readEntry(jar, "velocity-plugin.json");
        Matcher matcher = descriptor == null ? null : VERSION.matcher(descriptor);
        if (descriptor == null || !ID.matcher(descriptor).find() || !matcher.find()) {
            throw new IOException("The downloaded jar isn't DiscordLinked");
        }
        if (!matcher.group(1).equals(version)) {
            throw new IOException("The downloaded jar is version " + matcher.group(1) + ", not " + version);
        }
        Matcher main = MAIN.matcher(descriptor);
        checkJava(jar, main.find() ? main.group(1) : null);
    }

    @Override
    protected void stage(Path jar, ReleaseClient.Release release) throws IOException {
        discardStaged();
        Files.move(jar, workDirectory().resolve(release.proxy().file()), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    protected String staged() {
        Path jar = stagedJar();
        if (jar == null) {
            return null;
        }
        try {
            String descriptor = readEntry(jar, "velocity-plugin.json");
            Matcher matcher = descriptor == null ? null : VERSION.matcher(descriptor);
            return matcher != null && matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Path stagedJar() {
        Path directory = workDirectory();
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void discardStaged() {
        Path directory = workDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(jars::add);
        } catch (IOException e) {
            log(true, "Could not look in " + directory + ": " + e.getMessage());
        }
        for (Path jar : jars) {
            try {
                Files.deleteIfExists(jar);
            } catch (IOException e) {
                log(true, "Could not delete " + jar + ": " + e.getMessage());
            }
        }
    }

    @Override
    protected Policy policy() {
        return plugin.config().updatePolicy();
    }

    @Override
    protected int playersOnline() {
        return plugin.proxy().getPlayerCount();
    }

    @Override
    protected void warnPlayers(Duration in) {
        var message = plugin.messages().minecraft("minecraft.update.restart-warning", "time", duration(in.toMillis()));
        for (Player player : plugin.proxy().getAllPlayers()) {
            player.sendMessage(message);
        }
    }

    @Override
    protected void restartServer() {
        plugin.proxy().shutdown(plugin.messages().minecraft("minecraft.update.restart-kick"));
    }

    @Override
    protected void log(boolean warning, String message) {
        if (warning) {
            plugin.logger().warn(message);
        } else {
            plugin.logger().info(message);
        }
    }

    @Override
    protected void released(ReleaseClient.Release release) {
        // Backend servers hear about it now rather than at their own next check.
        plugin.proxy().getScheduler().buildTask(plugin, plugin.community()::sendUpdateSettings).schedule();
    }

    /**
     * Called last when the proxy shuts down: puts a downloaded update in place of the running jar, so the next
     * start runs it.
     */
    public void install() {
        Path jar = stagedJar();
        Path own = ownJar();
        String version = staged();
        if (jar == null || own == null || version == null || !Version.newer(version,
                DiscordLinked.VERSION)) {
            return;
        }
        Path target = own.resolveSibling(jar.getFileName());
        if (target.equals(own)) {
            target = own.resolveSibling("DiscordLinked-" + version + ".jar");
        }
        try {
            Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.logger().warn("Could not put DiscordLinked {} in place ({}); it stays in {}", version, e.getMessage(),
                    workDirectory());
            return;
        }
        try {
            // The old jar is kept in update/previous/ (not in plugins/, where Velocity would load it), to go back to
            // by hand if the new version doesn't work out. On Linux and macOS moving a running jar is fine.
            Path previous = workDirectory().resolve("previous");
            Files.createDirectories(previous);
            try (Stream<Path> old = Files.list(previous)) {
                for (Path file : old.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.move(own, previous.resolve(own.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(workDirectory().resolve(SWAP_PROBLEM));
            plugin.logger().info("Installed DiscordLinked {} ({}); it runs from the next start. The old jar is in {}.",
                    version, target.getFileName(), previous);
        } catch (IOException e) {
            // Two DiscordLinked jars would both be loaded (or neither): put the new one back. (Moving a jar that's
            // in use fails on Windows.)
            try {
                Files.move(target, jar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException back) {
                plugin.logger().error("Could not move {} back after failing to delete {}: delete one of the two "
                        + "DiscordLinked jars in the plugins folder by hand", target, own, back);
                return;
            }
            try {
                Files.writeString(workDirectory().resolve(SWAP_PROBLEM), "The downloaded version couldn't replace "
                        + own.getFileName() + ", because it couldn't be moved while the proxy was running ("
                        + e.getMessage() + "). Stop the proxy, delete it and move " + jar.getFileName()
                        + " from plugins/discordlinked/update/ into the plugins folder.");
            } catch (IOException ignored) {
                // the log says it
            }
            plugin.logger().warn("Could not replace {} with DiscordLinked {}: {}. With the proxy stopped, delete it and "
                    + "move {} into the plugins folder.", own.getFileName(), version, e.getMessage(), jar);
        }
    }
}
