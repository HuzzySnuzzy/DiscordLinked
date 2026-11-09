package com.huzzysnuzzy.discordlinked.backend;

import com.huzzysnuzzy.discordlinked.update.ReleaseClient;
import com.huzzysnuzzy.discordlinked.update.Updater;
import com.huzzysnuzzy.discordlinked.update.Version;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Updates DiscordLinkedBackend (see {@link Updater}). A downloaded jar goes in the server's update folder
 * (plugins/update, bukkit.yml's settings.update-folder): when the server starts, Paper replaces this plugin's jar
 * with it before loading plugins.
 *
 * <p>The settings come from the proxy (UPDATES, kept in update/settings.properties so they last through restarts)
 * once it has sent them; until then from config.yml's auto-update.
 */
final class BackendUpdater extends Updater {

    private static final String NAME = "DiscordLinkedBackend";
    private static final Pattern YAML_NAME = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");
    private static final Pattern YAML_VERSION = Pattern.compile("(?m)^version:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");
    private static final Pattern YAML_API = Pattern.compile("(?m)^api-version:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");
    private static final Pattern YAML_MAIN = Pattern.compile("(?m)^main:\\s*['\"]?([^'\"\\s]+)['\"]?\\s*$");
    private static final String SETTINGS_FILE = "settings.properties";

    private final DiscordLinkedBackend plugin;
    /** The proxy's settings for the backend servers; null until it has sent some. */
    private volatile Policy fromProxy;

    BackendUpdater(DiscordLinkedBackend plugin) {
        super(NAME + "/" + plugin.getPluginMeta().getVersion() + " (updater; +https://github.com/"
                + ReleaseClient.DEFAULT_REPOSITORY + ")", "DiscordLinkedBackend updater");
        this.plugin = plugin;
        fromProxy = loadProxySettings();
    }

    /**
     * The proxy's UPDATES: its settings for the backend servers and the newest release it knows of. Kept for the
     * next start; a newer release than this one is looked at soon. Where updates come from stays this server's own
     * setting (config.yml): a plugin message must never be able to point it at someone else's code.
     */
    void fromProxy(String mode, String restart, String time, String repository, int checkHours, String latest) {
        Policy policy = Policy.of(mode, restart, time, plugin.settings().updatePolicy.repository(),
                Integer.toString(checkHours));
        if (!policy.equals(fromProxy)) {
            fromProxy = policy;
            saveProxySettings(policy);
        }
        hint(latest);
    }

    @Override
    protected String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    protected ReleaseClient.Asset asset(ReleaseClient.Release release) {
        return release.backend();
    }

    @Override
    protected Path workDirectory() {
        return plugin.getDataFolder().toPath().resolve("update");
    }

    private static Path updateFolder() {
        File folder = Bukkit.getUpdateFolderFile();
        return folder.toPath();
    }

    @Override
    protected void verify(Path jar, String version) throws IOException {
        String yml = readEntry(jar, "plugin.yml");
        String name = yml == null ? null : find(YAML_NAME, yml);
        if (!NAME.equals(name)) {
            throw new IOException("The downloaded jar isn't " + NAME);
        }
        String found = find(YAML_VERSION, yml);
        if (!version.equals(found)) {
            throw new IOException("The downloaded jar is version " + found + ", not " + version);
        }
        // Paper won't load a plugin made for a newer Minecraft, and the updater couldn't fix that from inside it.
        String api = find(YAML_API, yml);
        String minecraft = Bukkit.getMinecraftVersion();
        if (api != null && Version.valid(minecraft) && Version.newer(api, minecraft)) {
            throw new IOException("The new version needs Minecraft " + api + " or newer; this server runs " + minecraft);
        }
        checkJava(jar, find(YAML_MAIN, yml));
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    protected void stage(Path jar, ReleaseClient.Release release) throws IOException {
        Path folder = updateFolder();
        Files.createDirectories(folder);
        discardStaged();
        // Paper matches it to this plugin by the name in its plugin.yml and keeps the file's name.
        Path target = folder.resolve(release.backend().file());
        try {
            Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Another drive than the plugin's folder: copy it over, then into place.
            Path temp = folder.resolve(release.backend().file() + ".part");
            Files.copy(jar, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** This plugin's jars in the update folder, with their versions. */
    private List<Object[]> stagedJars() {
        List<Object[]> found = new ArrayList<>();
        Path folder = updateFolder();
        if (!Files.isDirectory(folder)) {
            return found;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try {
                    String yml = readEntry(file, "plugin.yml");
                    if (yml != null && NAME.equals(find(YAML_NAME, yml))) {
                        found.add(new Object[] {file, find(YAML_VERSION, yml)});
                    }
                } catch (IOException e) {
                    // not a jar, or not ours
                }
            }
        } catch (IOException e) {
            log(true, "Could not look in " + folder + ": " + e.getMessage());
        }
        return found;
    }

    @Override
    protected String staged() {
        List<Object[]> jars = stagedJars();
        return jars.isEmpty() ? null : (String) jars.get(0)[1];
    }

    @Override
    protected void discardStaged() {
        for (Object[] jar : stagedJars()) {
            try {
                Files.deleteIfExists((Path) jar[0]);
            } catch (IOException e) {
                log(true, "Could not delete " + jar[0] + ": " + e.getMessage());
            }
        }
    }

    @Override
    protected Policy policy() {
        Policy proxy = fromProxy;
        Policy own = plugin.settings().updatePolicy;
        // config.yml's repository even after a reload changed it.
        return proxy == null ? own : new Policy(proxy.mode(), proxy.restart(), proxy.time(), own.repository(),
                proxy.checkHours());
    }

    /** Whether the settings came from the proxy (rather than config.yml). */
    boolean followsProxy() {
        return fromProxy != null;
    }

    @Override
    protected int playersOnline() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    protected void warnPlayers(Duration in) {
        Component message = plugin.settings().message("update-restart-warning",
                Placeholder.unparsed("time", duration(in.toMillis())));
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
    }

    @Override
    protected void restartServer() {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                // spigot.yml's restart-script if there is one; otherwise this stops the server.
                Bukkit.spigot().restart();
            } catch (RuntimeException | LinkageError e) {
                Bukkit.shutdown();
            }
        });
    }

    @Override
    protected void log(boolean warning, String message) {
        if (warning) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().info(message);
        }
    }

    /** The proxy's settings are applied with this server's own repository (see {@link #fromProxy}). */
    private Policy loadProxySettings() {
        Path file = workDirectory().resolve(SETTINGS_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            return Policy.of(props.getProperty("mode"), props.getProperty("restart"), props.getProperty("time"),
                    plugin.settings().updatePolicy.repository(), props.getProperty("check-hours"));
        } catch (IOException | IllegalArgumentException e) {
            log(true, "Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private void saveProxySettings(Policy policy) {
        Properties props = new Properties();
        props.setProperty("mode", policy.mode().key());
        props.setProperty("restart", policy.restart().key());
        props.setProperty("time", policy.timeText());
        props.setProperty("repository", policy.repository());
        props.setProperty("check-hours", Integer.toString(policy.checkHours()));
        try {
            Path directory = workDirectory();
            Files.createDirectories(directory);
            Path temp = directory.resolve(SETTINGS_FILE + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Update settings from the proxy (website: Settings -> Updates). Written by the plugin.");
            }
            Files.move(temp, directory.resolve(SETTINGS_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log(true, "Could not save the proxy's update settings: " + e.getMessage());
        }
    }
}
