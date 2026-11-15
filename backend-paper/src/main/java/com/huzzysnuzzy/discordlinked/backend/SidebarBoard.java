package com.huzzysnuzzy.discordlinked.backend;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The on-screen leaderboard (/dl leaderboard true) as a sidebar: the proxy sends the title and lines, this shows
 * them on a scoreboard of the player's own, without score numbers. Turning it off (or leaving) puts back the
 * scoreboard they had. Not on Folia, which has no scoreboards: there the proxy uses a boss bar.
 */
final class SidebarBoard implements Listener {

    private static final String OBJECTIVE = "dl_sidebar";

    private record Shown(Scoreboard board, Scoreboard before, int lines) {
    }

    private final DiscordLinkedBackend plugin;
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();

    SidebarBoard(DiscordLinkedBackend plugin) {
        this.plugin = plugin;
    }

    /** Lines are told apart by these (one colour code each), and the text shown is the score's custom name. */
    private static String entry(int line) {
        return "§" + Integer.toHexString(line) + "§r";
    }

    void show(Player player, Component title, List<Component> lines) {
        if (plugin.folia()) {
            return;
        }
        player.getScheduler().run(plugin, task -> {
            Shown current = shown.get(player.getUniqueId());
            Scoreboard board = current == null ? Bukkit.getScoreboardManager().getNewScoreboard() : current.board();
            Objective objective = board.getObjective(OBJECTIVE);
            if (objective == null) {
                objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title);
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
                objective.numberFormat(NumberFormat.blank());
            }
            objective.displayName(title);
            int count = Math.min(lines.size(), 15);
            for (int i = 0; i < count; i++) {
                Score score = objective.getScore(entry(i));
                score.setScore(count - i);
                score.customName(lines.get(i));
            }
            for (int i = count; current != null && i < current.lines(); i++) {
                board.resetScores(entry(i));
            }
            Scoreboard before = current == null ? player.getScoreboard() : current.before();
            if (player.getScoreboard() != board) {
                player.setScoreboard(board);
            }
            shown.put(player.getUniqueId(), new Shown(board, before, count));
        }, null);
    }

    void hide(Player player) {
        Shown current = shown.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.getScoreboard() == current.board()) {
                player.setScoreboard(current.before() != null ? current.before() : Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }
}
