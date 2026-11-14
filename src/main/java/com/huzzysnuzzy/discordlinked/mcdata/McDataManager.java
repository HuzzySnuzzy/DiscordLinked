package com.huzzysnuzzy.discordlinked.mcdata;

import com.huzzysnuzzy.discordlinked.AuditLog.EventTypes;
import com.huzzysnuzzy.discordlinked.DiscordLinked;
import com.huzzysnuzzy.discordlinked.storage.Database;
import com.huzzysnuzzy.discordlinked.storage.Database.DlBackup;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Resets of players' data from the website's MC data page (settings owner only), and their undo.
 *
 * <p>The Minecraft part (advancements, statistics, items, experience...) is done by DiscordLinkedBackend on each
 * chosen server: this sends it there (MCDATA) through a player on that server, as soon as there is one, and again
 * every 30 seconds until the server answers (MCDATA_STATE). The server resets the players online right away and the
 * others when they next join. DiscordLinked's own data (credits, playtime...) is reset here at once, and what it was is
 * kept with the operation so an undo can give it back.
 */
public final class McDataManager {

    /** What DiscordLinkedBackend can reset, in the order the website shows them. */
    public static final List<String> MC = List.of("advancements", "stats", "inventory", "enderchest", "xp", "health",
            "location", "recipes");
    /** What of DiscordLinked's own data can be reset. */
    public static final List<String> DL = List.of("advcredits", "playtime", "credits", "micro", "streak");
    private static final long RESEND_MILLIS = 30_000;
    private static final long GIVE_UP_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_SUMMARY_BYTES = 8192;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiscordLinked plugin;

    public McDataManager(DiscordLinked plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.proxy().getScheduler().buildTask(plugin, this::deliverSafely)
                .delay(10, TimeUnit.SECONDS).repeat(30, TimeUnit.SECONDS).schedule();
    }

    /** Someone arrived on a server: anything waiting for it can go now (once the backend plugin is listening). */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        plugin.proxy().getScheduler().buildTask(plugin, this::deliverSafely).delay(3, TimeUnit.SECONDS).schedule();
    }

    /**
     * Resets {@code mc} on {@code servers} and {@code dl} here, for {@code target} (null = everyone). Returns the
     * operation. The caller checked the lists.
     */
    public Database.McDataOp reset(UUID target, String targetName, List<String> servers, List<String> mc,
                                   List<String> dl, String actor) throws SQLException {
        byte[] random = new byte[6];
        RANDOM.nextBytes(random);
        String token = "op-" + HexFormat.of().formatHex(random);
        // The operation, DiscordLinked's reset and its backup are saved together, or not at all.
        Database.McDataOp op = plugin.database().insertMcDataOp(token, actor, target, targetName,
                mc.isEmpty() ? List.of() : servers, mc, dl,
                backup -> new String[] {summary(backup), toJson(backup).toString()});
        String who = target == null ? "everyone" : targetName;
        plugin.audit().log(target, target == null ? null : targetName, null, EventTypes.MCDATA_RESET,
                "#" + op.id() + ": reset " + String.join(", ", labels(mc, dl)) + " of " + who
                        + (mc.isEmpty() ? "" : " on " + String.join(", ", servers)), actor, "");
        deliverSafely();
        return plugin.database().mcDataOp(op.id()).orElse(op);
    }

    /** Undoes an operation. Null if done, else why not. */
    public String undo(long id, String actor) throws SQLException {
        Optional<Database.McDataOp> found = plugin.database().mcDataOp(id);
        if (found.isEmpty()) {
            return "There's no such reset.";
        }
        Database.McDataOp op = found.get();
        // Marked undone and DiscordLinked's data given back together, or neither.
        if (!plugin.database().undoMcDataOp(id, actor, json -> fromJson(DataObject.fromJson(json)))) {
            return "That reset was already undone.";
        }
        UUID target = op.targetUuid() == null ? null : UUID.fromString(op.targetUuid());
        plugin.audit().log(target, op.targetName(), null, EventTypes.MCDATA_RESET, "#" + id + ": undone", actor, "");
        deliverSafely();
        return null;
    }

    private void deliverSafely() {
        try {
            deliver();
        } catch (SQLException | RuntimeException e) {
            plugin.logger().warn("Could not send MC data resets to the servers", e);
        }
    }

    /** Sends what each server hasn't acknowledged yet, through anyone on it. */
    private synchronized void deliver() throws SQLException {
        long now = System.currentTimeMillis();
        for (Database.McDataServer row : plugin.database().mcDataUndelivered()) {
            Optional<Database.McDataOp> found = plugin.database().mcDataOp(row.opId());
            if (found.isEmpty()) {
                continue;
            }
            Database.McDataOp op = found.get();
            // After 30 days the servers' backups are gone, and a server that never answered (no plugin) is given up.
            if (now - op.createdAt() > GIVE_UP_MILLIS) {
                continue;
            }
            boolean undo = op.undoneAt() != null;
            Long sent = undo ? row.undoSentAt() : row.sentAt();
            if (sent != null && now - sent < RESEND_MILLIS) {
                continue;
            }
            ServerConnection carrier = carrier(row.server());
            if (carrier == null) {
                continue;
            }
            try {
                plugin.community().sendMcData(carrier, op.token(), undo ? "undo" : "reset",
                        op.targetUuid() == null ? "" : op.targetUuid(), String.join(",", op.mc()), op.createdAt(),
                        op.actor());
                plugin.database().markMcDataSent(op.id(), row.server(), undo);
            } catch (IllegalStateException e) {
                // the player left just now: the next try picks someone else
            }
        }
    }

    private ServerConnection carrier(String server) {
        for (Player player : plugin.proxy().getAllPlayers()) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent() && connection.get().getServerInfo().getName().equalsIgnoreCase(server)) {
                return connection.get();
            }
        }
        return null;
    }

    /** A server's MCDATA_STATE. */
    public void state(String server, String token, boolean undone, int applied, int restored) {
        try {
            Optional<Database.McDataOp> op = plugin.database().mcDataOpByToken(token);
            if (op.isPresent()) {
                plugin.database().mcDataState(op.get().id(), server, undone, Math.max(0, applied), Math.max(0, restored));
            }
        } catch (SQLException e) {
            plugin.logger().warn("Could not save a server's MC data state", e);
        }
    }

    /** A server's MCSUMMARY of a player there. Only a JSON object of plain values is kept. */
    public void summary(UUID uuid, String server, String json) {
        if (json.length() > MAX_SUMMARY_BYTES) {
            return;
        }
        try {
            DataObject parsed = DataObject.fromJson(json);
            DataObject clean = DataObject.empty();
            for (String key : parsed.keys()) {
                Object value = parsed.get(key);
                if (key.matches("[A-Za-z]{1,40}") && value instanceof Number) {
                    clean.put(key, value);
                }
            }
            plugin.database().saveMcSummary(uuid, server, clean.toString());
        } catch (RuntimeException | SQLException e) {
            plugin.logger().debug("Ignored an unreadable MC data summary from {}: {}", server, e.getMessage());
        }
    }

    // ---- Labels, and the backup of DiscordLinked's data as JSON ----

    public static String label(String key) {
        return switch (key) {
            case "advancements" -> "advancements";
            case "stats" -> "statistics";
            case "inventory" -> "inventory";
            case "enderchest" -> "ender chest";
            case "xp" -> "experience";
            case "health" -> "health & hunger";
            case "location" -> "position & spawn point";
            case "recipes" -> "unlocked recipes";
            case "advcredits" -> "advancement credits";
            case "playtime" -> "playtime";
            case "credits" -> "credits";
            case "micro" -> "microcredits";
            case "streak" -> "login streak";
            default -> key;
        };
    }

    private static List<String> labels(List<String> mc, List<String> dl) {
        List<String> labels = new ArrayList<>();
        mc.forEach(key -> labels.add(label(key)));
        dl.forEach(key -> labels.add(label(key)));
        return labels;
    }

    /** "3 players: 1,200 credits, 40 advancements…" for the history list. */
    static String summary(DlBackup b) {
        List<String> parts = new ArrayList<>();
        if (!b.advancements().isEmpty()) {
            parts.add(b.advancements().size() + " advancement credit record" + (b.advancements().size() == 1 ? "" : "s"));
        }
        if (!b.playtime().isEmpty()) {
            long hours = b.playtime().stream().mapToLong(Database.PlaytimeRow::active).sum() / 3600;
            parts.add(String.format("%,d", hours) + " hours of playtime");
        }
        if (!b.credits().isEmpty()) {
            parts.add(String.format("%,d", b.credits().stream().mapToLong(Database.BalanceRow::amount).sum()) + " credits");
        }
        long micro = b.micro().stream().mapToLong(Database.BalanceRow::amount).sum()
                + b.groups().stream().mapToLong(Database.GroupBalanceRow::balance).sum();
        if (micro > 0) {
            parts.add(String.format("%,d", micro) + " microcredits");
        }
        if (!b.streaks().isEmpty()) {
            parts.add(b.streaks().size() + " login streak" + (b.streaks().size() == 1 ? "" : "s"));
        }
        return parts.isEmpty() ? "nothing to reset" : String.join(", ", parts);
    }

    static DataObject toJson(DlBackup b) {
        DataArray adv = DataArray.empty();
        b.advancements().forEach(r -> adv.add(DataObject.empty().put("u", r.uuid()).put("a", r.advancement())
                .put("t", r.title()).put("f", r.frame()).put("s", r.server()).put("c", r.credits()).put("at", r.earnedAt())));
        DataArray time = DataArray.empty();
        b.playtime().forEach(r -> time.add(DataObject.empty().put("u", r.uuid()).put("a", r.active()).put("k", r.afk())
                .put("p", r.unpaid())));
        DataArray credits = DataArray.empty();
        b.credits().forEach(r -> credits.add(DataObject.empty().put("u", r.uuid()).put("n", r.name()).put("v", r.amount())));
        DataArray micro = DataArray.empty();
        b.micro().forEach(r -> micro.add(DataObject.empty().put("u", r.uuid()).put("n", r.name()).put("v", r.amount())));
        DataArray groups = DataArray.empty();
        b.groups().forEach(r -> groups.add(DataObject.empty().put("g", r.id()).put("v", r.balance())));
        DataArray streaks = DataArray.empty();
        b.streaks().forEach(r -> streaks.add(DataObject.empty().put("u", r.uuid()).put("d", r.lastDay())
                .put("s", r.streak()).put("b", r.best())));
        return DataObject.empty().put("advancements", adv).put("playtime", time).put("credits", credits)
                .put("micro", micro).put("groups", groups).put("streaks", streaks);
    }

    static DlBackup fromJson(DataObject json) {
        List<Database.AdvRow> adv = new ArrayList<>();
        DataArray a = json.getArray("advancements");
        for (int i = 0; i < a.length(); i++) {
            DataObject r = a.getObject(i);
            adv.add(new Database.AdvRow(r.getString("u"), r.getString("a"), r.getString("t"), r.getString("f"),
                    r.getString("s"), r.getLong("c"), r.getLong("at")));
        }
        List<Database.PlaytimeRow> time = new ArrayList<>();
        DataArray t = json.getArray("playtime");
        for (int i = 0; i < t.length(); i++) {
            DataObject r = t.getObject(i);
            time.add(new Database.PlaytimeRow(r.getString("u"), r.getLong("a"), r.getLong("k"), r.getLong("p")));
        }
        List<Database.BalanceRow> credits = balances(json.getArray("credits"));
        List<Database.BalanceRow> micro = balances(json.getArray("micro"));
        List<Database.GroupBalanceRow> groups = new ArrayList<>();
        DataArray g = json.getArray("groups");
        for (int i = 0; i < g.length(); i++) {
            DataObject r = g.getObject(i);
            groups.add(new Database.GroupBalanceRow(r.getLong("g"), r.getLong("v")));
        }
        List<Database.StreakRow> streaks = new ArrayList<>();
        DataArray s = json.getArray("streaks");
        for (int i = 0; i < s.length(); i++) {
            DataObject r = s.getObject(i);
            streaks.add(new Database.StreakRow(r.getString("u"), r.getString("d"), r.getInt("s"), r.getInt("b")));
        }
        return new DlBackup(adv, time, credits, micro, groups, streaks);
    }

    private static List<Database.BalanceRow> balances(DataArray array) {
        List<Database.BalanceRow> rows = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            DataObject r = array.getObject(i);
            rows.add(new Database.BalanceRow(r.getString("u"), r.getString("n"), r.getLong("v")));
        }
        return rows;
    }
}
