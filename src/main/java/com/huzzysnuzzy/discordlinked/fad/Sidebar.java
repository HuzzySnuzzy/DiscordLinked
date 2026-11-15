package com.huzzysnuzzy.discordlinked.fad;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.PluginConfig;
import com.huzzysnuzzy.discordlinked.TimeFormat;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The on-screen leaderboard players switch on with /dl leaderboard true. Every sidebar-seconds it switches between
 * the top players by credits (plus what their microcredits are worth, while selling is open) and the most active
 * players, with the player's own place below. Shown as a sidebar by DiscordLinkedBackend 0.31.0+ (not on Folia,
 * which has no scoreboards); anywhere else as a boss bar from the proxy.
 */
public final class Sidebar {

    /** The boards, in the order they're shown. */
    enum Board { CREDITS, ACTIVE }

    private final DiscordLinked plugin;
    private final Community community;
    /** Online players who have it on (read from the database when they join). */
    private final Set<UUID> on = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private volatile Board board = Board.CREDITS;
    /** Seconds since the board last switched. */
    private int elapsed;

    Sidebar(DiscordLinked plugin, Community community) {
        this.plugin = plugin;
        this.community = community;
    }

    /** A one-second clock, so a changed sidebar-seconds applies at once. */
    void start() {
        plugin.proxy().getScheduler().buildTask(plugin, this::second)
                .delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
    }

    private synchronized void second() {
        if (++elapsed >= plugin.config().sidebarSeconds()) {
            elapsed = 0;
            next();
        }
    }

    /** The next board, for everyone who has it on. */
    synchronized void next() {
        board = board == Board.CREDITS ? Board.ACTIVE : Board.CREDITS;
        showAll();
    }

    private void showAll() {
        if (on.isEmpty()) {
            return;
        }
        List<Database.BoardRow> rows = rows(board);
        if (rows == null) {
            return;
        }
        for (UUID uuid : on) {
            plugin.proxy().getPlayer(uuid).ifPresent(player -> show(player, board, rows));
        }
    }

    public boolean isOn(UUID uuid) {
        return on.contains(uuid);
    }

    /** /dl leaderboard true|false. */
    public void set(Player player, boolean show) throws SQLException {
        plugin.database().setSidebar(player.getUniqueId(), player.getUsername(), show);
        if (show) {
            on.add(player.getUniqueId());
            List<Database.BoardRow> rows = rows(board);
            if (rows != null) {
                show(player, board, rows);
            }
        } else {
            on.remove(player.getUniqueId());
            hide(player);
        }
    }

    /** A player got on a server: show it there too (once they've finished joining), the right way for that server. */
    void joined(Player player, String server) {
        UUID uuid = player.getUniqueId();
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            try {
                if (plugin.database().sidebar(uuid)) {
                    on.add(uuid);
                } else {
                    on.remove(uuid);
                    return;
                }
            } catch (SQLException e) {
                plugin.logger().warn("Could not read the leaderboard setting of {}", player.getUsername(), e);
                return;
            }
            if (player.getCurrentServer().filter(c -> c.getServerInfo().getName().equals(server)).isEmpty()) {
                return;
            }
            List<Database.BoardRow> rows = rows(board);
            if (rows != null) {
                show(player, board, rows);
            }
        }).delay(2, TimeUnit.SECONDS).schedule();
    }

    void left(UUID uuid) {
        on.remove(uuid);
        bars.remove(uuid);
    }

    // ---- Drawing ----

    private List<Database.BoardRow> rows(Board which) {
        PluginConfig c = plugin.config();
        if (!c.fadEnabled()) {
            return null;
        }
        long rate = community.market().open() ? c.microPerCredit() : 0;
        try {
            return plugin.database().board(which == Board.ACTIVE, rate);
        } catch (SQLException e) {
            plugin.logger().warn("Could not read the leaderboard", e);
            return null;
        }
    }

    private String value(Board which, long value) {
        return which == Board.ACTIVE ? TimeFormat.hours(Duration.ofSeconds(value)) : String.format("%,d", value);
    }

    /** The title and lines for one player: the top players (them highlighted), then their own place if lower. */
    private void show(Player player, Board which, List<Database.BoardRow> rows) {
        PluginConfig c = plugin.config();
        String titleKey = which == Board.ACTIVE ? "minecraft.sidebar.title-active" : "minecraft.sidebar.title-credits";
        Component title = plugin.messages().minecraft(titleKey, "currency", c.creditsNamePlural());
        List<Component> lines = new ArrayList<>();
        int size = Math.min(c.sidebarSize(), rows.size());
        int mine = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).uuid().equals(player.getUniqueId())) {
                mine = i;
                break;
            }
        }
        for (int i = 0; i < size; i++) {
            Database.BoardRow row = rows.get(i);
            lines.add(plugin.messages().minecraft(i == mine ? "minecraft.sidebar.line-you" : "minecraft.sidebar.line",
                    "rank", String.valueOf(i + 1), "name", row.name(), "value", value(which, row.value())));
        }
        if (rows.isEmpty()) {
            lines.add(plugin.messages().minecraft("minecraft.sidebar.empty"));
        }
        if (mine < 0 || mine >= size) {
            lines.add(Component.empty());
            lines.add(mine < 0 ? plugin.messages().minecraft("minecraft.sidebar.you-unranked")
                    : plugin.messages().minecraft("minecraft.sidebar.you", "rank", String.valueOf(mine + 1),
                            "value", value(which, rows.get(mine).value())));
        }
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection != null && plugin.servers().sidebarCapable(connection.getServerInfo().getName())) {
            hideBar(player);
            GsonComponentSerializer json = GsonComponentSerializer.gson();
            community.bridge().sidebar(connection, player.getUniqueId(), json.serialize(title),
                    lines.stream().map(json::serialize).toList());
            return;
        }
        // No sidebar here: a boss bar with the top three and their own place.
        List<Component> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            Database.BoardRow row = rows.get(i);
            parts.add(plugin.messages().minecraft("minecraft.sidebar.bar-entry", "rank", String.valueOf(i + 1),
                    "name", row.name(), "value", value(which, row.value())));
        }
        Component text = plugin.messages().minecraft("minecraft.sidebar.bar",
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("title", title),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("top",
                                Component.join(net.kyori.adventure.text.JoinConfiguration.separator(
                                        plugin.messages().minecraft("minecraft.sidebar.bar-separator")), parts)),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("you", mine < 0
                                ? plugin.messages().minecraft("minecraft.sidebar.bar-unranked")
                                : plugin.messages().minecraft("minecraft.sidebar.bar-you", "rank", String.valueOf(mine + 1)))));
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(),
                u -> BossBar.bossBar(text, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
        bar.name(text);
        player.showBossBar(bar);
    }

    private void hide(Player player) {
        hideBar(player);
        player.getCurrentServer().ifPresent(c -> community.bridge().sidebarOff(c, player.getUniqueId()));
    }

    private void hideBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }
}
