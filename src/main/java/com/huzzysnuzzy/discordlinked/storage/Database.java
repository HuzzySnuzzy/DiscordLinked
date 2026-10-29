package com.huzzysnuzzy.discordlinked.storage;

import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite storage for account links, suspensions and the audit log.
 * One shared connection; methods are synchronized because SQLite allows a single writer anyway.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    public Database(Path file) throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        this.connection = dataSource.getConnection();
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS linked_accounts (
                        minecraft_uuid TEXT PRIMARY KEY,
                        minecraft_name TEXT NOT NULL,
                        discord_id     INTEGER NOT NULL UNIQUE,
                        linked_at      INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp      INTEGER NOT NULL,
                        minecraft_uuid TEXT,
                        minecraft_name TEXT,
                        discord_id     INTEGER,
                        event_type     TEXT NOT NULL,
                        detail         TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_uuid ON audit_log (minecraft_uuid, timestamp)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS suspensions (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        reason         TEXT NOT NULL,
                        created_by     TEXT NOT NULL,
                        created_at     INTEGER NOT NULL,
                        expires_at     INTEGER NOT NULL,
                        lifted_at      INTEGER,
                        lifted_by      TEXT,
                        lift_reason    TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_suspensions_uuid ON suspensions (minecraft_uuid)");
        }
        // Columns added after 0.2.0.
        addColumnIfMissing("linked_accounts", "discord_name", "TEXT");
        addColumnIfMissing("linked_accounts", "discord_avatar", "TEXT");
        addColumnIfMissing("audit_log", "actor", "TEXT");
        if (addColumnIfMissing("linked_accounts", "last_seen", "INTEGER")) {
            // Fill in from join/leave history recorded before this column existed.
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                        UPDATE linked_accounts SET last_seen = (
                            SELECT MAX(timestamp) FROM audit_log
                            WHERE audit_log.minecraft_uuid = linked_accounts.minecraft_uuid
                              AND event_type IN ('JOIN', 'LEAVE', 'SERVER_SWITCH'))""");
            }
        }

        // Added in 0.5.0.
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_log (event_type)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS player_notes (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        note           TEXT NOT NULL,
                        author         TEXT NOT NULL,
                        created_at     INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_notes_uuid ON player_notes (minecraft_uuid)");
        }
        try (Statement st = connection.createStatement()) {
            // Added in 0.8.0.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inventory_snapshots (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        minecraft_name TEXT NOT NULL,
                        server         TEXT NOT NULL,
                        reason         TEXT NOT NULL,
                        detail         TEXT,
                        created_at     INTEGER NOT NULL,
                        stacks         INTEGER NOT NULL,
                        display        TEXT NOT NULL,
                        data           BLOB NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_uuid ON inventory_snapshots (minecraft_uuid, created_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS pending_restores (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        snapshot_id    INTEGER NOT NULL,
                        include_ender  INTEGER NOT NULL,
                        actor          TEXT NOT NULL,
                        reason         TEXT NOT NULL,
                        requested_at   INTEGER NOT NULL
                    )""");
        }
        if (!tableExists("player_ips")) {
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE player_ips (
                            minecraft_uuid TEXT NOT NULL,
                            ip             TEXT NOT NULL,
                            first_seen     INTEGER NOT NULL,
                            last_seen      INTEGER NOT NULL,
                            times          INTEGER NOT NULL,
                            PRIMARY KEY (minecraft_uuid, ip)
                        )""");
                st.execute("CREATE INDEX idx_ips_ip ON player_ips (ip)");
            }
            backfillIps();
        }
        try (Statement st = connection.createStatement()) {
            // Added in 0.13.0: the settings owner's per-item Shop changes.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS shop_items (
                        item     TEXT PRIMARY KEY,
                        disabled INTEGER NOT NULL,
                        percent  INTEGER NOT NULL
                    )""");
        }
        if (!tableExists("known_players")) {
            // Added in 0.12.0: everyone who has played, also from the servers' own player data.
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE known_players (
                            minecraft_uuid TEXT PRIMARY KEY,
                            minecraft_name TEXT NOT NULL,
                            first_played   INTEGER,
                            last_played    INTEGER
                        )""");
                // Start with everyone who already joined through the proxy, and every linked player.
                st.execute("""
                        INSERT INTO known_players (minecraft_uuid, minecraft_name, first_played, last_played)
                        SELECT minecraft_uuid,
                               (SELECT a2.minecraft_name FROM audit_log a2 WHERE a2.minecraft_uuid = a.minecraft_uuid
                                  AND a2.minecraft_name IS NOT NULL ORDER BY a2.id DESC LIMIT 1),
                               MIN(timestamp), MAX(timestamp)
                        FROM audit_log a
                        WHERE minecraft_uuid IS NOT NULL AND event_type IN ('JOIN', 'LEAVE')
                        GROUP BY minecraft_uuid""");
                st.execute("""
                        INSERT OR IGNORE INTO known_players (minecraft_uuid, minecraft_name, first_played, last_played)
                        SELECT minecraft_uuid, minecraft_name, linked_at, last_seen FROM linked_accounts""");
            }
        }
        // Added in 0.14.0: the server a player was last on (where their logout spot and bed are), and login streaks.
        addColumnIfMissing("known_players", "last_server", "TEXT");
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS login_streaks (
                        minecraft_uuid TEXT PRIMARY KEY,
                        last_day       TEXT NOT NULL,
                        streak         INTEGER NOT NULL,
                        best           INTEGER NOT NULL
                    )""");
        }
        try (Statement st = connection.createStatement()) {
            // Added in 0.10.0: credits, interactions and advancements from DiscordLinkedFAD.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fad_accounts (
                        minecraft_uuid TEXT PRIMARY KEY,
                        minecraft_name TEXT NOT NULL,
                        credits        INTEGER NOT NULL DEFAULT 0,
                        earned         INTEGER NOT NULL DEFAULT 0,
                        active_seconds INTEGER NOT NULL DEFAULT 0,
                        afk_seconds    INTEGER NOT NULL DEFAULT 0,
                        unpaid_seconds INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fad_transactions (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        amount         INTEGER NOT NULL,
                        balance        INTEGER NOT NULL,
                        kind           TEXT NOT NULL,
                        detail         TEXT,
                        actor          TEXT,
                        created_at     INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_fad_tx_uuid ON fad_transactions (minecraft_uuid, id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fad_advancements (
                        minecraft_uuid TEXT NOT NULL,
                        advancement    TEXT NOT NULL,
                        title          TEXT NOT NULL,
                        frame          TEXT NOT NULL,
                        server         TEXT NOT NULL,
                        credits        INTEGER NOT NULL,
                        earned_at      INTEGER NOT NULL,
                        PRIMARY KEY (minecraft_uuid, advancement)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fad_interactions (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        action         TEXT NOT NULL,
                        actor_uuid     TEXT,
                        actor_discord  INTEGER,
                        actor_name     TEXT NOT NULL,
                        target_uuid    TEXT,
                        target_discord INTEGER,
                        target_name    TEXT,
                        source         TEXT NOT NULL,
                        text           TEXT NOT NULL,
                        created_at     INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_fad_int_actor ON fad_interactions (actor_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_fad_int_target ON fad_interactions (target_uuid)");
            // "mc:<uuid>" or "dc:<discord id>" of people who don't want to be picked for interactions.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS fad_opt_outs (
                        subject        TEXT PRIMARY KEY,
                        created_at     INTEGER NOT NULL
                    )""");
        }
        // Added in 0.16.0: microcredits from selling items, and what the market remembers.
        addColumnIfMissing("fad_accounts", "micro", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("fad_accounts", "micro_earned", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("shop_items", "sell_disabled", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("shop_items", "sell_percent", "INTEGER NOT NULL DEFAULT 100");
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS micro_transactions (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_uuid TEXT NOT NULL,
                        amount         INTEGER NOT NULL,
                        balance        INTEGER NOT NULL,
                        kind           TEXT NOT NULL,
                        detail         TEXT,
                        actor          TEXT,
                        created_at     INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_micro_tx_uuid ON micro_transactions (minecraft_uuid, id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_micro_tx_time ON micro_transactions (created_at)");
            // How much of each item was sold lately (for dropping prices), in thousandths of a credit's worth.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS market_items (
                        item       TEXT PRIMARY KEY,
                        pressure   REAL NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS market_state (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
        }
        try (Statement st = connection.createStatement()) {
            // Added in 0.17.0: the Discord message log (settings owner only).
            st.execute("""
                    CREATE TABLE IF NOT EXISTS discord_messages (
                        message_id  INTEGER PRIMARY KEY,
                        channel_id  INTEGER NOT NULL,
                        author_id   INTEGER NOT NULL,
                        author_name TEXT NOT NULL,
                        author_avatar TEXT,
                        bot         INTEGER NOT NULL,
                        content     TEXT NOT NULL,
                        attachments TEXT,
                        created_at  INTEGER NOT NULL,
                        edited_at   INTEGER,
                        original    TEXT,
                        deleted_at  INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_discord_messages_channel ON discord_messages (channel_id, created_at)");
        }
        addColumnIfMissing("discord_messages", "channel_name", "TEXT");
        // Added in 0.18.0: cosmetics bought with credits (prefixes, trails, profile cards, KeepInventory).
        boolean newCosmetics = !tableExists("cosmetics");
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cosmetics (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind       TEXT NOT NULL,
                        name       TEXT NOT NULL,
                        value      TEXT NOT NULL,
                        price      INTEGER NOT NULL,
                        for_sale   INTEGER NOT NULL,
                        sort       INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cosmetic_owned (
                        minecraft_uuid TEXT NOT NULL,
                        cosmetic_id    INTEGER NOT NULL,
                        paid           INTEGER NOT NULL,
                        acquired_at    INTEGER NOT NULL,
                        equipped_at    INTEGER,
                        PRIMARY KEY (minecraft_uuid, cosmetic_id)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cosmetic_owned_item ON cosmetic_owned (cosmetic_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS web_users (
                        discord_id  INTEGER PRIMARY KEY,
                        name        TEXT NOT NULL,
                        avatar      TEXT,
                        first_login INTEGER NOT NULL,
                        last_login  INTEGER NOT NULL
                    )""");
        }
        boolean newStyles = addColumnIfMissing("cosmetics", "style", "TEXT");
        addColumnIfMissing("cosmetic_owned", "style", "TEXT");
        if (newCosmetics) {
            seedCosmetics();
            seedAuras();
        } else if (newStyles) {
            seedAuras(); // upgrading from 0.18: standing-still particles are new
        }
    }

    /** A first set so the shop isn't empty; the settings owner can change or delete all of them. */
    private void seedCosmetics() throws SQLException {
        Object[][] defaults = {
                {"prefix", "Builder", "&b[Builder]", 150},
                {"prefix", "Farmer", "&a[Farmer]", 150},
                {"prefix", "Night Owl", "&9[Night Owl]", 200},
                {"prefix", "Cutie", "&d[✿ Cutie]", 250},
                {"prefix", "Tycoon", "&6&l[Tycoon]", 400},
                {"trail", "Hearts", "heart", 300},
                {"trail", "Flames", "flame", 250},
                {"trail", "Soul fire", "soul_fire_flame", 300},
                {"trail", "Music notes", "note", 250},
                {"trail", "Sparkles", "end_rod", 350},
                {"trail", "Cherry petals", "cherry_leaves", 400},
                {"trail", "Snowfall", "snowflake", 250},
                {"trail", "Magic runes", "enchant", 200},
                {"keepinv", "KeepInventory", "", 750},
        };
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cosmetics (kind, name, value, price, for_sale, sort, created_at) VALUES (?, ?, ?, ?, 1, ?, ?)")) {
            int sort = 0;
            for (Object[] row : defaults) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setLong(4, ((Integer) row[3]).longValue());
                ps.setInt(5, sort++);
                ps.setLong(6, Instant.now().toEpochMilli());
                ps.executeUpdate();
            }
        }
    }

    /**
     * Standing-still particles to start with. Each is a particle type: whoever buys one picks its shape, animation and
     * colour on their profile.
     */
    private void seedAuras() throws SQLException {
        Object[][] defaults = {
                {"Hearts", "heart", null, 350},
                {"Sparkles", "end_rod", null, 350},
                {"Soul fire", "soul_fire_flame", null, 350},
                {"Glow", "glow", null, 300},
                {"Witch magic", "witch", null, 300},
                {"Cherry petals", "cherry_leaves", null, 400},
        };
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO cosmetics (kind, name, value, style, price, "
                + "for_sale, sort, created_at) VALUES ('aura', ?, ?, ?, ?, 1, (SELECT COALESCE(MAX(sort), 0) + 1 FROM cosmetics), ?)")) {
            for (Object[] row : defaults) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setLong(4, ((Integer) row[3]).longValue());
                ps.setLong(5, Instant.now().toEpochMilli());
                ps.executeUpdate();
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Builds the IP table from the "ip=..." details already in the audit log. */
    private void backfillIps() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT minecraft_uuid, detail, timestamp FROM audit_log WHERE minecraft_uuid IS NOT NULL "
                        + "AND event_type IN ('JOIN', 'DENIED_UNLINKED', 'DENIED_SUSPENDED') AND detail LIKE '%ip=%' "
                        + "ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String detail = rs.getString("detail");
                String ip = detail.substring(detail.lastIndexOf("ip=") + 3).trim();
                if (!ip.isEmpty()) {
                    recordIp(UUID.fromString(rs.getString("minecraft_uuid")), ip, rs.getLong("timestamp"));
                }
            }
        }
    }

    /** Returns true if the column was added. */
    private boolean addColumnIfMissing(String table, String column, String type) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equalsIgnoreCase(column)) {
                    return false;
                }
            }
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
        return true;
    }

    // ---- Links ----

    public synchronized Optional<LinkedAccount> findByUuid(UUID uuid) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE minecraft_uuid = ?", uuid.toString());
    }

    public synchronized Optional<LinkedAccount> findByDiscordId(long discordId) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE discord_id = ?", discordId);
    }

    public synchronized Optional<LinkedAccount> findByName(String minecraftName) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE minecraft_name = ? COLLATE NOCASE", minecraftName);
    }

    public synchronized List<LinkedAccount> allLinked() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM linked_accounts ORDER BY minecraft_name COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            List<LinkedAccount> accounts = new ArrayList<>();
            while (rs.next()) {
                accounts.add(readAccount(rs));
            }
            return accounts;
        }
    }

    public synchronized void link(UUID uuid, String minecraftName, long discordId,
                                  String discordName, String discordAvatar) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO linked_accounts (minecraft_uuid, minecraft_name, discord_id, linked_at, discord_name, discord_avatar) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, minecraftName);
            ps.setLong(3, discordId);
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.setString(5, discordName);
            ps.setString(6, discordAvatar);
            ps.executeUpdate();
        }
    }

    public synchronized boolean unlink(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM linked_accounts WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void updateName(UUID uuid, String minecraftName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE linked_accounts SET minecraft_name = ? WHERE minecraft_uuid = ?")) {
            ps.setString(1, minecraftName);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateLastSeen(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE linked_accounts SET last_seen = ? WHERE minecraft_uuid = ?")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateDiscordProfile(long discordId, String discordName, String discordAvatar)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE linked_accounts SET discord_name = ?, discord_avatar = ? WHERE discord_id = ?")) {
            ps.setString(1, discordName);
            ps.setString(2, discordAvatar);
            ps.setLong(3, discordId);
            ps.executeUpdate();
        }
    }

    // ---- Players (linked or not) ----

    // ---- Shop ----

    public synchronized Map<String, ShopOverride> shopOverrides() throws SQLException {
        Map<String, ShopOverride> overrides = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM shop_items")) {
            while (rs.next()) {
                overrides.put(rs.getString("item"), new ShopOverride(rs.getString("item"),
                        rs.getInt("disabled") != 0, rs.getInt("percent"), rs.getInt("sell_disabled") != 0,
                        rs.getInt("sell_percent")));
            }
        }
        return overrides;
    }

    /** Saves an item's Shop and selling settings; the defaults (on, 100%) remove the row. */
    public synchronized void setShopOverride(String item, boolean disabled, int percent, boolean sellDisabled,
                                             int sellPercent) throws SQLException {
        if (!disabled && percent == 100 && !sellDisabled && sellPercent == 100) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM shop_items WHERE item = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shop_items (item, disabled, percent, sell_disabled, sell_percent) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (item) DO UPDATE SET disabled = excluded.disabled, percent = excluded.percent, "
                        + "sell_disabled = excluded.sell_disabled, sell_percent = excluded.sell_percent")) {
            ps.setString(1, item);
            ps.setInt(2, disabled ? 1 : 0);
            ps.setInt(3, percent);
            ps.setInt(4, sellDisabled ? 1 : 0);
            ps.setInt(5, sellPercent);
            ps.executeUpdate();
        }
    }

    // ---- Known players (everyone who has played) ----

    /**
     * Adds or updates players: the earliest first-played and latest last-played time win, and the name is
     * taken from whichever source saw them most recently.
     */
    public synchronized int upsertKnownPlayers(List<KnownPlayer> players) throws SQLException {
        return transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO known_players (minecraft_uuid, minecraft_name, first_played, last_played)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (minecraft_uuid) DO UPDATE SET
                        minecraft_name = CASE WHEN COALESCE(excluded.last_played, 0) >= COALESCE(last_played, 0)
                                              THEN excluded.minecraft_name ELSE minecraft_name END,
                        first_played = CASE WHEN first_played IS NULL THEN excluded.first_played
                                            WHEN excluded.first_played IS NULL THEN first_played
                                            ELSE MIN(first_played, excluded.first_played) END,
                        last_played = MAX(COALESCE(last_played, 0), COALESCE(excluded.last_played, 0))""")) {
                for (KnownPlayer player : players) {
                    ps.setString(1, player.uuid().toString());
                    ps.setString(2, player.name());
                    setNullableLong(ps, 3, player.firstPlayed() == null ? null : player.firstPlayed().toEpochMilli());
                    setNullableLong(ps, 4, player.lastPlayed() == null ? null : player.lastPlayed().toEpochMilli());
                    ps.addBatch();
                }
                return ps.executeBatch().length;
            }
        });
    }

    /** Records that a player is on right now. */
    public synchronized void touchKnownPlayer(UUID uuid, String name) throws SQLException {
        Instant now = Instant.now();
        upsertKnownPlayers(List.of(new KnownPlayer(uuid, name, now, now)));
    }

    public synchronized List<KnownPlayer> knownPlayers() throws SQLException {
        List<KnownPlayer> players = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM known_players ORDER BY minecraft_name COLLATE NOCASE")) {
            while (rs.next()) {
                players.add(readKnownPlayer(rs));
            }
        }
        return players;
    }

    /** Remembers someone who logged in to the website (with their current Discord name and avatar). */
    public synchronized void recordWebLogin(long discordId, String name, String avatar) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO web_users (discord_id, name, avatar, first_login, last_login) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (discord_id) DO UPDATE SET name = excluded.name, avatar = excluded.avatar,
                    last_login = excluded.last_login""")) {
            ps.setLong(1, discordId);
            ps.setString(2, name);
            ps.setString(3, avatar);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /** Everyone who has logged in to the website, most recent first. */
    public synchronized List<WebUser> webUsers() throws SQLException {
        List<WebUser> users = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM web_users ORDER BY last_login DESC")) {
            while (rs.next()) {
                users.add(new WebUser(rs.getLong("discord_id"), rs.getString("name"), rs.getString("avatar"),
                        Instant.ofEpochMilli(rs.getLong("first_login")), Instant.ofEpochMilli(rs.getLong("last_login"))));
            }
        }
        return users;
    }

    public synchronized void setLastServer(UUID uuid, String name, String server) throws SQLException {
        touchKnownPlayer(uuid, name);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE known_players SET last_server = ? WHERE minecraft_uuid = ?")) {
            ps.setString(1, server);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<String> lastServer(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT last_server FROM known_players WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    // ---- Login streaks ----

    /**
     * Counts a join on {@code today}: the first join of a day continues the streak if they also joined the day
     * before, and starts it again at 1 otherwise. Later joins the same day change nothing.
     */
    public synchronized LoginStreak recordLogin(UUID uuid, java.time.LocalDate today) throws SQLException {
        return transaction(() -> {
            LoginStreak old = loginStreak(uuid).orElse(null);
            if (old != null && today.equals(old.lastDay())) {
                return old;
            }
            int streak = old != null && today.minusDays(1).equals(old.lastDay()) ? old.current() + 1 : 1;
            int best = Math.max(streak, old == null ? 0 : old.best());
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO login_streaks (minecraft_uuid, last_day, streak, best) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (minecraft_uuid) DO UPDATE SET last_day = excluded.last_day, "
                            + "streak = excluded.streak, best = excluded.best")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, today.toString());
                ps.setInt(3, streak);
                ps.setInt(4, best);
                ps.executeUpdate();
            }
            return new LoginStreak(streak, best, today, true);
        });
    }

    public synchronized Map<UUID, LoginStreak> loginStreaks() throws SQLException {
        Map<UUID, LoginStreak> streaks = new HashMap<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM login_streaks")) {
            while (rs.next()) {
                streaks.put(UUID.fromString(rs.getString("minecraft_uuid")), new LoginStreak(rs.getInt("streak"),
                        rs.getInt("best"), java.time.LocalDate.parse(rs.getString("last_day")), false));
            }
        }
        return streaks;
    }

    public synchronized Optional<LoginStreak> loginStreak(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM login_streaks WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new LoginStreak(rs.getInt("streak"), rs.getInt("best"),
                        java.time.LocalDate.parse(rs.getString("last_day")), false)) : Optional.empty();
            }
        }
    }

    public synchronized int knownPlayerCount() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM (SELECT minecraft_uuid FROM known_players "
                     + "UNION SELECT minecraft_uuid FROM linked_accounts)")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized Optional<KnownPlayer> knownPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM known_players WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readKnownPlayer(rs)) : Optional.empty();
            }
        }
    }

    private static KnownPlayer readKnownPlayer(ResultSet rs) throws SQLException {
        Long first = nullableLong(rs, "first_played");
        Long last = nullableLong(rs, "last_played");
        return new KnownPlayer(UUID.fromString(rs.getString("minecraft_uuid")), rs.getString("minecraft_name"),
                first == null || first == 0 ? null : Instant.ofEpochMilli(first),
                last == null || last == 0 ? null : Instant.ofEpochMilli(last));
    }

    /** A linked player, or any player that has played or appears in the audit log (using their most recent name). */
    public synchronized Optional<PlayerRef> findPlayer(UUID uuid) throws SQLException {
        Optional<LinkedAccount> linked = findByUuid(uuid);
        if (linked.isPresent()) {
            return linked.map(PlayerRef::of);
        }
        Optional<KnownPlayer> known = knownPlayer(uuid);
        if (known.isPresent()) {
            return Optional.of(new PlayerRef(uuid, known.get().name(), null));
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT minecraft_name FROM audit_log WHERE minecraft_uuid = ? AND minecraft_name IS NOT NULL "
                        + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PlayerRef(uuid, rs.getString(1), null)) : Optional.empty();
            }
        }
    }

    /** Players matching a Minecraft name, UUID, Discord name or Discord ID; linked players first. */
    public synchronized List<PlayerRef> searchPlayers(String query, int limit) throws SQLException {
        Map<UUID, PlayerRef> found = new java.util.LinkedHashMap<>();
        String like = "%" + escapeLike(query) + "%";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM linked_accounts WHERE minecraft_name LIKE ? ESCAPE '\\' OR discord_name LIKE ? ESCAPE '\\' "
                        + "OR minecraft_uuid = ? OR CAST(discord_id AS TEXT) = ? ORDER BY minecraft_name COLLATE NOCASE LIMIT ?")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, query.toLowerCase());
            ps.setString(4, query);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LinkedAccount account = readAccount(rs);
                    found.put(account.uuid(), PlayerRef.of(account));
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT minecraft_uuid, minecraft_name, MAX(id) AS last_id FROM audit_log "
                        + "WHERE minecraft_uuid IS NOT NULL AND minecraft_name IS NOT NULL "
                        + "AND (minecraft_name LIKE ? ESCAPE '\\' OR minecraft_uuid = ?) "
                        + "GROUP BY minecraft_uuid ORDER BY last_id DESC LIMIT ?")) {
            ps.setString(1, like);
            ps.setString(2, query.toLowerCase());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && found.size() < limit) {
                    UUID uuid = UUID.fromString(rs.getString("minecraft_uuid"));
                    found.putIfAbsent(uuid, new PlayerRef(uuid, rs.getString("minecraft_name"), null));
                }
            }
        }
        if (found.size() < limit) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT minecraft_uuid, minecraft_name FROM known_players WHERE minecraft_name LIKE ? ESCAPE '\\' "
                            + "OR minecraft_uuid = ? ORDER BY last_played DESC LIMIT ?")) {
                ps.setString(1, like);
                ps.setString(2, query.toLowerCase());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && found.size() < limit) {
                        UUID uuid = UUID.fromString(rs.getString("minecraft_uuid"));
                        found.putIfAbsent(uuid, new PlayerRef(uuid, rs.getString("minecraft_name"), null));
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** When an unlinked player last connected (or tried to), or null. Linked players use last_seen. */
    public synchronized Instant lastActivity(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(COALESCE((SELECT MAX(timestamp) FROM audit_log WHERE minecraft_uuid = ?1 "
                        + "AND event_type IN ('JOIN', 'LEAVE', 'DENIED_UNLINKED', 'DENIED_SUSPENDED')), 0), "
                        + "COALESCE((SELECT last_played FROM known_players WHERE minecraft_uuid = ?1), 0))")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                long value = rs.next() ? rs.getLong(1) : 0;
                return value == 0 ? null : Instant.ofEpochMilli(value);
            }
        }
    }

    // ---- Notes ----

    public synchronized PlayerNote addNote(UUID uuid, String note, String author) throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_notes (minecraft_uuid, note, author, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, note);
            ps.setString(3, author);
            ps.setLong(4, now.toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new PlayerNote(keys.getLong(1), note, author, now);
            }
        }
    }

    public synchronized List<PlayerNote> notes(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_notes WHERE minecraft_uuid = ? ORDER BY id DESC")) {
            ps.setString(1, uuid.toString());
            List<PlayerNote> notes = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(new PlayerNote(rs.getLong("id"), rs.getString("note"), rs.getString("author"),
                            Instant.ofEpochMilli(rs.getLong("created_at"))));
                }
            }
            return notes;
        }
    }

    // ---- IPs ----

    public synchronized void recordIp(UUID uuid, String ip) throws SQLException {
        recordIp(uuid, ip, Instant.now().toEpochMilli());
    }

    private void recordIp(UUID uuid, String ip, long timestamp) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_ips (minecraft_uuid, ip, first_seen, last_seen, times) VALUES (?, ?, ?, ?, 1) "
                        + "ON CONFLICT (minecraft_uuid, ip) DO UPDATE SET last_seen = excluded.last_seen, times = times + 1")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setLong(3, timestamp);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
        }
    }

    public synchronized List<PlayerIp> ips(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM player_ips WHERE minecraft_uuid = ? ORDER BY last_seen DESC")) {
            ps.setString(1, uuid.toString());
            List<PlayerIp> ips = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ips.add(new PlayerIp(rs.getString("ip"), Instant.ofEpochMilli(rs.getLong("first_seen")),
                            Instant.ofEpochMilli(rs.getLong("last_seen")), rs.getInt("times")));
                }
            }
            return ips;
        }
    }

    /** Other players who have connected from the same IP. */
    public synchronized List<UUID> playersOnIp(String ip, UUID except) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT minecraft_uuid FROM player_ips WHERE ip = ? AND minecraft_uuid != ? ORDER BY last_seen DESC")) {
            ps.setString(1, ip);
            ps.setString(2, except.toString());
            List<UUID> players = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(UUID.fromString(rs.getString(1)));
                }
            }
            return players;
        }
    }

    // ---- Suspensions ----

    public synchronized Suspension addSuspension(UUID uuid, String reason, String createdBy, Instant expiresAt)
            throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO suspensions (minecraft_uuid, reason, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, reason);
            ps.setString(3, createdBy);
            ps.setLong(4, now.toEpochMilli());
            ps.setLong(5, expiresAt.toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Suspension(keys.getLong(1), uuid, reason, createdBy, now, expiresAt, null, null, null);
            }
        }
    }

    public synchronized Optional<Suspension> activeSuspension(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM suspensions WHERE minecraft_uuid = ? AND lifted_at IS NULL AND expires_at > ? "
                        + "ORDER BY expires_at DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, Instant.now().toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSuspension(rs)) : Optional.empty();
            }
        }
    }

    public synchronized Map<UUID, Suspension> activeSuspensions() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM suspensions WHERE lifted_at IS NULL AND expires_at > ? ORDER BY expires_at")) {
            ps.setLong(1, Instant.now().toEpochMilli());
            Map<UUID, Suspension> result = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Suspension s = readSuspension(rs);
                    result.put(s.uuid(), s); // ordered by expiry, so the longest one wins
                }
            }
            return result;
        }
    }

    /** Every suspension the player has had, newest first. */
    public synchronized List<Suspension> suspensions(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM suspensions WHERE minecraft_uuid = ? ORDER BY id DESC")) {
            ps.setString(1, uuid.toString());
            List<Suspension> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSuspension(rs));
                }
            }
            return result;
        }
    }

    /** Changes an active suspension's reason and end time. Returns false if it's no longer active. */
    public synchronized boolean editSuspension(long id, String reason, Instant expiresAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE suspensions SET reason = ?, expires_at = ? WHERE id = ? AND lifted_at IS NULL AND expires_at > ?")) {
            ps.setString(1, reason);
            ps.setLong(2, expiresAt.toEpochMilli());
            ps.setLong(3, id);
            ps.setLong(4, Instant.now().toEpochMilli());
            return ps.executeUpdate() > 0;
        }
    }

    /** Ends every active suspension for the player. Returns false if there was none. */
    public synchronized boolean liftSuspension(UUID uuid, String liftedBy, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE suspensions SET lifted_at = ?, lifted_by = ?, lift_reason = ? "
                        + "WHERE minecraft_uuid = ? AND lifted_at IS NULL AND expires_at > ?")) {
            long now = Instant.now().toEpochMilli();
            ps.setLong(1, now);
            ps.setString(2, liftedBy);
            ps.setString(3, reason);
            ps.setString(4, uuid.toString());
            ps.setLong(5, now);
            return ps.executeUpdate() > 0;
        }
    }

    // ---- Audit log ----

    /** Returns the stored entry, with its id. */
    public synchronized AuditEntry logEvent(UUID uuid, String minecraftName, Long discordId,
                                            String eventType, String detail, String actor) throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (timestamp, minecraft_uuid, minecraft_name, discord_id, event_type, detail, actor) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, now.toEpochMilli());
            ps.setString(2, uuid == null ? null : uuid.toString());
            ps.setString(3, minecraftName);
            if (discordId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, discordId);
            }
            ps.setString(5, eventType);
            ps.setString(6, detail);
            ps.setString(7, actor);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new AuditEntry(keys.getLong(1), now, uuid, minecraftName, discordId, eventType, detail, actor);
            }
        }
    }

    public synchronized List<AuditEntry> recentEvents(UUID uuid, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM audit_log WHERE minecraft_uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            return readEntries(ps);
        }
    }

    /**
     * Newest-first page of the audit log.
     *
     * @param types        event types to include, or empty for all
     * @param playerSearch part of a Minecraft name or actor, or empty
     * @param player       only this player's entries, or null
     * @param beforeId     only entries with a smaller id (for paging), or 0
     */
    public synchronized List<AuditEntry> searchEvents(Collection<String> types, String playerSearch, UUID player,
                                                      long beforeId, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (player != null) {
            sql.append(" AND minecraft_uuid = ?");
            params.add(player.toString());
        }
        if (!types.isEmpty()) {
            sql.append(" AND event_type IN (").append("?,".repeat(types.size() - 1)).append("?)");
            params.addAll(types);
        }
        if (!playerSearch.isEmpty()) {
            String like = "%" + escapeLike(playerSearch) + "%";
            sql.append(" AND (minecraft_name LIKE ? ESCAPE '\\' OR actor LIKE ? ESCAPE '\\')");
            params.add(like);
            params.add(like);
        }
        if (beforeId > 0) {
            sql.append(" AND id < ?");
            params.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(limit);
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return readEntries(ps);
        }
    }

    // ---- Inventory snapshots ----

    public synchronized long addSnapshot(UUID uuid, String name, String server, String reason, String detail,
                                         Instant createdAt, int stacks, String display, byte[] data) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO inventory_snapshots (minecraft_uuid, minecraft_name, server, reason, detail, created_at, "
                        + "stacks, display, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, server);
            ps.setString(4, reason);
            ps.setString(5, detail);
            ps.setLong(6, createdAt.toEpochMilli());
            ps.setInt(7, stacks);
            ps.setString(8, display);
            ps.setBytes(9, data);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Keeps only the newest {@code keep} snapshots for a player. */
    public synchronized void pruneSnapshots(UUID uuid, int keep) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM inventory_snapshots WHERE minecraft_uuid = ? AND id NOT IN "
                        + "(SELECT id FROM inventory_snapshots WHERE minecraft_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, keep);
            ps.executeUpdate();
        }
    }

    /** Newest first, without the item data. */
    public synchronized List<InventorySnapshotRecord> snapshots(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, minecraft_uuid, minecraft_name, server, reason, detail, created_at, stacks FROM inventory_snapshots "
                        + "WHERE minecraft_uuid = ? ORDER BY created_at DESC, id DESC")) {
            ps.setString(1, uuid.toString());
            List<InventorySnapshotRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSnapshot(rs, false));
                }
            }
            return result;
        }
    }

    public synchronized Optional<InventorySnapshotRecord> snapshot(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM inventory_snapshots WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSnapshot(rs, true)) : Optional.empty();
            }
        }
    }

    public synchronized long addPendingRestore(UUID uuid, long snapshotId, boolean includeEnder, String actor,
                                               String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pending_restores (minecraft_uuid, snapshot_id, include_ender, actor, reason, requested_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, snapshotId);
            ps.setInt(3, includeEnder ? 1 : 0);
            ps.setString(4, actor);
            ps.setString(5, reason);
            ps.setLong(6, Instant.now().toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public synchronized Optional<PendingRestore> pendingRestore(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM pending_restores WHERE minecraft_uuid = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PendingRestore(rs.getLong("id"), uuid, rs.getLong("snapshot_id"),
                        rs.getInt("include_ender") != 0, rs.getString("actor"), rs.getString("reason")));
            }
        }
    }

    /** Removes every queued restore for the player (only the newest one is ever applied). */
    public synchronized void clearPendingRestores(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM pending_restores WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private static InventorySnapshotRecord readSnapshot(ResultSet rs, boolean full) throws SQLException {
        return new InventorySnapshotRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("minecraft_uuid")),
                rs.getString("minecraft_name"),
                rs.getString("server"),
                rs.getString("reason"),
                rs.getString("detail"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getInt("stacks"),
                full ? rs.getString("display") : null,
                full ? rs.getBytes("data") : null);
    }

    // ---- Maintenance ----

    /** Deletes log entries older than {@code before}; {@code onlyTypes} limits it to those types, else all but them. */
    public synchronized int deleteOldEvents(Instant before, Collection<String> types, boolean onlyTypes) throws SQLException {
        String in = "?,".repeat(types.size() - 1) + "?";
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM audit_log WHERE timestamp < ? AND event_type "
                + (onlyTypes ? "IN" : "NOT IN") + " (" + in + ")")) {
            ps.setLong(1, before.toEpochMilli());
            int i = 2;
            for (String type : types) {
                ps.setString(i++, type);
            }
            return ps.executeUpdate();
        }
    }

    public synchronized int deleteSnapshotsBefore(Instant before) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM inventory_snapshots WHERE created_at < ?")) {
            ps.setLong(1, before.toEpochMilli());
            return ps.executeUpdate();
        }
    }

    /** IP history rows not seen since {@code before} (kept in step with the log retention). */
    public synchronized int deleteIpsBefore(Instant before) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM player_ips WHERE last_seen < ?")) {
            ps.setLong(1, before.toEpochMilli());
            return ps.executeUpdate();
        }
    }

    public synchronized Map<String, Long> counts() throws SQLException {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        try (Statement st = connection.createStatement()) {
            for (String[] q : new String[][] {
                    {"linkedPlayers", "SELECT COUNT(*) FROM linked_accounts"},
                    {"logEntries", "SELECT COUNT(*) FROM audit_log"},
                    {"chatEntries", "SELECT COUNT(*) FROM audit_log WHERE event_type IN ('CHAT', 'COMMAND')"},
                    {"snapshots", "SELECT COUNT(*) FROM inventory_snapshots"},
                    {"snapshotBytes", "SELECT COALESCE(SUM(LENGTH(data) + LENGTH(display)), 0) FROM inventory_snapshots"},
                    {"notes", "SELECT COUNT(*) FROM player_notes"}}) {
                try (ResultSet rs = st.executeQuery(q[1])) {
                    counts.put(q[0], rs.next() ? rs.getLong(1) : 0);
                }
            }
        }
        return counts;
    }

    /** Gives freed space back to the disk. Locks the database briefly. */
    public synchronized void compact() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            st.execute("VACUUM");
        }
    }

    // ---- Community: credits, advancements, interactions (DiscordLinkedFAD) ----

    private static final Map<String, String> LEADERBOARD_ORDER = Map.of(
            "credits", "credits", "earned", "earned", "playtime", "active_seconds",
            "advancements", "advancements", "given", "given", "received", "received",
            "micro", "micro", "microEarned", "micro_earned");

    /**
     * Adds credits (or takes them, with a negative amount) and records why.
     *
     * @return the new balance, or -1 (and nothing changes) if it would go below zero
     */
    public synchronized long addCredits(UUID uuid, String name, long amount, String kind, String detail, String actor)
            throws SQLException {
        return addCredits(uuid, name, amount, kind, detail, actor, true);
    }

    /**
     * Adds (or, negative, takes) credits; returns the new balance, or -1 and changes nothing if that would go
     * below 0. {@code countsAsEarned}: whether a positive amount adds to "earned in total" (not for refunds).
     */
    public synchronized long addCredits(UUID uuid, String name, long amount, String kind, String detail, String actor,
                                        boolean countsAsEarned) throws SQLException {
        return transaction(() -> {
            ensureCreditAccount(uuid, name);
            long balance = creditBalance(uuid) + amount;
            if (balance < 0) {
                return -1L;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fad_accounts SET credits = credits + ?, earned = earned + ? WHERE minecraft_uuid = ?")) {
                ps.setLong(1, amount);
                ps.setLong(2, countsAsEarned ? Math.max(0, amount) : 0);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            insertCreditTransaction(uuid, amount, balance, kind, detail, actor, Instant.now());
            return balance;
        });
    }

    /**
     * Adds {@code seconds} of playtime to everyone online, in one go. Active players earn {@code rewardAmount} for
     * every full {@code rewardSeconds} of active time; the rest carries over to the next call. Playtime rewards
     * are added to one ledger row per player per day (since {@code dayStart}) so the ledger stays small.
     *
     * @return the players who earned something just now
     */
    public synchronized List<PlaytimeReward> addPlaytime(Map<UUID, String> active, Map<UUID, String> afk, long seconds,
                                                         long rewardSeconds, long rewardAmount, Instant dayStart)
            throws SQLException {
        return transaction(() -> {
            List<PlaytimeReward> rewards = new ArrayList<>();
            try (PreparedStatement addAfk = connection.prepareStatement(
                    "UPDATE fad_accounts SET afk_seconds = afk_seconds + ? WHERE minecraft_uuid = ?");
                 PreparedStatement addActive = connection.prepareStatement(
                         "UPDATE fad_accounts SET active_seconds = active_seconds + ?, unpaid_seconds = "
                                 + "CASE WHEN ? THEN unpaid_seconds + ? ELSE 0 END WHERE minecraft_uuid = ? "
                                 + "RETURNING unpaid_seconds, credits");
                 PreparedStatement pay = connection.prepareStatement(
                         "UPDATE fad_accounts SET credits = credits + ?, earned = earned + ?, "
                                 + "unpaid_seconds = unpaid_seconds - ? WHERE minecraft_uuid = ?")) {
                for (Map.Entry<UUID, String> player : afk.entrySet()) {
                    ensureCreditAccount(player.getKey(), player.getValue());
                    addAfk.setLong(1, seconds);
                    addAfk.setString(2, player.getKey().toString());
                    addAfk.executeUpdate();
                }
                boolean rewarding = rewardSeconds > 0 && rewardAmount > 0;
                for (Map.Entry<UUID, String> player : active.entrySet()) {
                    UUID uuid = player.getKey();
                    ensureCreditAccount(uuid, player.getValue());
                    addActive.setLong(1, seconds);
                    addActive.setBoolean(2, rewarding);
                    addActive.setLong(3, seconds);
                    addActive.setString(4, uuid.toString());
                    long unpaid;
                    long credits;
                    try (ResultSet rs = addActive.executeQuery()) {
                        rs.next();
                        unpaid = rs.getLong(1);
                        credits = rs.getLong(2);
                    }
                    long intervals = rewarding ? unpaid / rewardSeconds : 0;
                    if (intervals == 0) {
                        continue;
                    }
                    long amount = intervals * rewardAmount;
                    pay.setLong(1, amount);
                    pay.setLong(2, amount);
                    pay.setLong(3, intervals * rewardSeconds);
                    pay.setString(4, uuid.toString());
                    pay.executeUpdate();
                    long balance = credits + amount;
                    if (!mergePlaytimeTransaction(uuid, amount, balance, dayStart)) {
                        insertCreditTransaction(uuid, amount, balance, "PLAYTIME", "Active play", null, Instant.now());
                    }
                    rewards.add(new PlaytimeReward(uuid, amount, balance));
                }
            }
            return rewards;
        });
    }

    /** Adds to today's playtime row if the player's latest row is one. */
    private boolean mergePlaytimeTransaction(UUID uuid, long amount, long balance, Instant dayStart) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fad_transactions SET amount = amount + ?, balance = ?, created_at = ? WHERE id = "
                        + "(SELECT id FROM fad_transactions WHERE minecraft_uuid = ? ORDER BY id DESC LIMIT 1) "
                        + "AND kind = 'PLAYTIME' AND created_at >= ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, balance);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.setString(4, uuid.toString());
            ps.setLong(5, dayStart.toEpochMilli());
            return ps.executeUpdate() > 0;
        }
    }

    private void ensureCreditAccount(UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fad_accounts (minecraft_uuid, minecraft_name) VALUES (?, ?) "
                        + "ON CONFLICT (minecraft_uuid) DO UPDATE SET minecraft_name = excluded.minecraft_name")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private long creditBalance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT credits FROM fad_accounts WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private void insertCreditTransaction(UUID uuid, long amount, long balance, String kind, String detail, String actor,
                                         Instant at) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fad_transactions (minecraft_uuid, amount, balance, kind, detail, actor, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, amount);
            ps.setLong(3, balance);
            ps.setString(4, kind);
            ps.setString(5, detail);
            ps.setString(6, actor);
            ps.setLong(7, at.toEpochMilli());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<CreditAccount> creditAccount(UUID uuid) throws SQLException {
        return findCreditAccount("SELECT * FROM fad_accounts WHERE minecraft_uuid = ?", uuid.toString());
    }

    public synchronized Optional<CreditAccount> creditAccountByName(String name) throws SQLException {
        return findCreditAccount("SELECT * FROM fad_accounts WHERE minecraft_name = ? COLLATE NOCASE", name);
    }

    private Optional<CreditAccount> findCreditAccount(String sql, String param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new CreditAccount(UUID.fromString(rs.getString("minecraft_uuid")),
                        rs.getString("minecraft_name"), rs.getLong("credits"), rs.getLong("earned"),
                        rs.getLong("active_seconds"), rs.getLong("afk_seconds"), rs.getLong("micro"),
                        rs.getLong("micro_earned"))) : Optional.empty();
            }
        }
    }

    /** @param sort credits, earned, playtime, advancements, given or received */
    public synchronized List<LeaderboardRow> leaderboard(String sort, int limit) throws SQLException {
        String order = LEADERBOARD_ORDER.getOrDefault(sort, "credits");
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT a.minecraft_uuid, a.minecraft_name, a.credits, a.earned, a.active_seconds, a.micro, a.micro_earned, "
                        + "(SELECT COUNT(*) FROM fad_advancements v WHERE v.minecraft_uuid = a.minecraft_uuid) AS advancements, "
                        + "(SELECT COUNT(*) FROM fad_interactions i WHERE i.actor_uuid = a.minecraft_uuid) AS given, "
                        + "(SELECT COUNT(*) FROM fad_interactions i WHERE i.target_uuid = a.minecraft_uuid) AS received "
                        + "FROM fad_accounts a ORDER BY " + order + " DESC, a.minecraft_name COLLATE NOCASE LIMIT ?")) {
            ps.setInt(1, limit);
            List<LeaderboardRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LeaderboardRow(UUID.fromString(rs.getString("minecraft_uuid")),
                            rs.getString("minecraft_name"), rs.getLong("credits"), rs.getLong("earned"),
                            rs.getLong("active_seconds"), rs.getLong("advancements"), rs.getLong("given"),
                            rs.getLong("received"), rs.getLong("micro"), rs.getLong("micro_earned")));
                }
            }
            return rows;
        }
    }

    /** 1 for the player with the most credits, or 0 if they have no account yet. */
    public synchronized long creditRank(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 + (SELECT COUNT(*) FROM fad_accounts o WHERE o.credits > a.credits) "
                        + "FROM fad_accounts a WHERE a.minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public synchronized List<CreditTransaction> creditTransactions(UUID uuid, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM fad_transactions WHERE minecraft_uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            List<CreditTransaction> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new CreditTransaction(rs.getLong("id"), rs.getLong("amount"), rs.getLong("balance"),
                            rs.getString("kind"), rs.getString("detail"), rs.getString("actor"),
                            Instant.ofEpochMilli(rs.getLong("created_at"))));
                }
            }
            return list;
        }
    }

    /** Records an advancement the first time a player makes it. False if they already had it. */
    public synchronized boolean addAdvancement(UUID uuid, String key, String title, String frame, String server,
                                               long credits) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO fad_advancements (minecraft_uuid, advancement, title, frame, server, credits, "
                        + "earned_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, key);
            ps.setString(3, title);
            ps.setString(4, frame);
            ps.setString(5, server);
            ps.setLong(6, credits);
            ps.setLong(7, Instant.now().toEpochMilli());
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized List<EarnedAdvancement> advancements(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM fad_advancements WHERE minecraft_uuid = ? ORDER BY earned_at DESC")) {
            ps.setString(1, uuid.toString());
            List<EarnedAdvancement> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new EarnedAdvancement(rs.getString("advancement"), rs.getString("title"),
                            rs.getString("frame"), rs.getString("server"), rs.getLong("credits"),
                            Instant.ofEpochMilli(rs.getLong("earned_at"))));
                }
            }
            return list;
        }
    }

    public synchronized InteractionRecord addInteraction(String action, UUID actorUuid, Long actorDiscord, String actorName,
                                                         UUID targetUuid, Long targetDiscord, String targetName,
                                                         String source, String text) throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO fad_interactions (action, actor_uuid, actor_discord, actor_name, target_uuid, target_discord, "
                        + "target_name, source, text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, action);
            ps.setString(2, actorUuid == null ? null : actorUuid.toString());
            setNullableLong(ps, 3, actorDiscord);
            ps.setString(4, actorName);
            ps.setString(5, targetUuid == null ? null : targetUuid.toString());
            setNullableLong(ps, 6, targetDiscord);
            ps.setString(7, targetName);
            ps.setString(8, source);
            ps.setString(9, text);
            ps.setLong(10, now.toEpochMilli());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new InteractionRecord(keys.getLong(1), action, actorUuid, actorDiscord, actorName, targetUuid,
                        targetDiscord, targetName, source, text, now);
            }
        }
    }

    public synchronized List<InteractionRecord> recentInteractions(int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM fad_interactions ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            List<InteractionRecord> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String actor = rs.getString("actor_uuid");
                    String target = rs.getString("target_uuid");
                    list.add(new InteractionRecord(rs.getLong("id"), rs.getString("action"),
                            actor == null ? null : UUID.fromString(actor), nullableLong(rs, "actor_discord"),
                            rs.getString("actor_name"), target == null ? null : UUID.fromString(target),
                            nullableLong(rs, "target_discord"), rs.getString("target_name"), rs.getString("source"),
                            rs.getString("text"), Instant.ofEpochMilli(rs.getLong("created_at"))));
                }
            }
            return list;
        }
    }

    /** How often each action was used, by everyone ({@code actor} null) or by one player, most used first. */
    public synchronized Map<String, Long> interactionCounts(UUID actor) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT action, COUNT(*) FROM fad_interactions"
                + (actor == null ? "" : " WHERE actor_uuid = ?") + " GROUP BY action ORDER BY 2 DESC, action")) {
            if (actor != null) {
                ps.setString(1, actor.toString());
            }
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
            return counts;
        }
    }

    /** Interactions a player started and was picked for: {given, received}. */
    public synchronized long[] interactionTotals(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM fad_interactions WHERE actor_uuid = ?), "
                        + "(SELECT COUNT(*) FROM fad_interactions WHERE target_uuid = ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    /** Server-wide numbers for the website's Community page. */
    public synchronized Map<String, Long> communityTotals(Instant dayAgo) throws SQLException {
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(credits), 0), COALESCE(SUM(earned), 0), "
                     + "COALESCE(SUM(active_seconds), 0), COALESCE(SUM(micro), 0), COALESCE(SUM(micro_earned), 0) "
                     + "FROM fad_accounts")) {
            rs.next();
            totals.put("players", rs.getLong(1));
            totals.put("credits", rs.getLong(2));
            totals.put("earned", rs.getLong(3));
            totals.put("activeSeconds", rs.getLong(4));
            totals.put("micro", rs.getLong(5));
            totals.put("microEarned", rs.getLong(6));
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM fad_advancements")) {
            rs.next();
            totals.put("advancements", rs.getLong(1));
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(created_at >= ?), 0) FROM fad_interactions")) {
            ps.setLong(1, dayAgo.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                totals.put("interactions", rs.getLong(1));
                totals.put("interactionsToday", rs.getLong(2));
            }
        }
        return totals;
    }

    /** Subjects are "mc:<uuid>" and "dc:<discord id>"; a linked person is opted out if either is. */
    public synchronized void setOptOut(Collection<String> subjects, boolean optedOut) throws SQLException {
        for (String subject : subjects) {
            try (PreparedStatement ps = connection.prepareStatement(optedOut
                    ? "INSERT OR IGNORE INTO fad_opt_outs (subject, created_at) VALUES (?, ?)"
                    : "DELETE FROM fad_opt_outs WHERE subject = ?")) {
                ps.setString(1, subject);
                if (optedOut) {
                    ps.setLong(2, Instant.now().toEpochMilli());
                }
                ps.executeUpdate();
            }
        }
    }

    public synchronized boolean isOptedOut(Collection<String> subjects) throws SQLException {
        for (String subject : subjects) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM fad_opt_outs WHERE subject = ?")) {
                ps.setString(1, subject);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public synchronized int deleteCreditTransactionsBefore(Instant before) throws SQLException {
        int deleted = 0;
        for (String table : List.of("fad_transactions", "micro_transactions")) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + table + " WHERE created_at < ?")) {
                ps.setLong(1, before.toEpochMilli());
                deleted += ps.executeUpdate();
            }
        }
        return deleted;
    }

    // ---- Cosmetics ----

    public synchronized List<Cosmetic> cosmetics() throws SQLException {
        List<Cosmetic> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM cosmetics ORDER BY sort, id")) {
            while (rs.next()) {
                list.add(new Cosmetic(rs.getLong("id"), rs.getString("kind"), rs.getString("name"), rs.getString("value"),
                        rs.getString("style"), rs.getLong("price"), rs.getInt("for_sale") != 0, rs.getInt("sort")));
            }
        }
        return list;
    }

    /** Saves a new cosmetic ({@code id} 0, added at the end of its kind) or changes one; returns its id. */
    public synchronized long saveCosmetic(Cosmetic c) throws SQLException {
        if (c.id() == 0) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cosmetics (kind, name, value, style, price, for_sale, sort, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM cosmetics), ?) RETURNING id")) {
                ps.setString(1, c.kind());
                ps.setString(2, c.name());
                ps.setString(3, c.value());
                ps.setString(4, c.style());
                ps.setLong(5, c.price());
                ps.setInt(6, c.forSale() ? 1 : 0);
                ps.setLong(7, Instant.now().toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cosmetics SET name = ?, value = ?, style = ?, price = ?, for_sale = ? WHERE id = ?")) {
            ps.setString(1, c.name());
            ps.setString(2, c.value());
            ps.setString(3, c.style());
            ps.setLong(4, c.price());
            ps.setInt(5, c.forSale() ? 1 : 0);
            ps.setLong(6, c.id());
            ps.executeUpdate();
        }
        return c.id();
    }

    /** Who had a deleted cosmetic, and the credits they got back. */
    public record Refund(UUID uuid, long amount, long balance) {
    }

    /** Deletes a cosmetic, gives everyone who paid for it their credits back, and returns who that was. */
    public synchronized List<Refund> deleteCosmetic(long id, String detail, String actor) throws SQLException {
        return transaction(() -> {
            List<UUID> owners = new ArrayList<>();
            List<Long> paid = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT minecraft_uuid, paid FROM cosmetic_owned WHERE cosmetic_id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        owners.add(UUID.fromString(rs.getString(1)));
                        paid.add(rs.getLong(2));
                    }
                }
            }
            List<Refund> refunds = new ArrayList<>();
            for (int i = 0; i < owners.size(); i++) {
                UUID uuid = owners.get(i);
                long amount = paid.get(i);
                if (amount <= 0) {
                    refunds.add(new Refund(uuid, 0, -1));
                    continue;
                }
                long balance = creditBalance(uuid) + amount;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE fad_accounts SET credits = credits + ? WHERE minecraft_uuid = ?")) {
                    ps.setLong(1, amount);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
                insertCreditTransaction(uuid, amount, balance, "COSMETIC", detail, actor, Instant.now());
                refunds.add(new Refund(uuid, amount, balance));
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM cosmetic_owned WHERE cosmetic_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM cosmetics WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return refunds;
        });
    }

    /** How many players have each cosmetic, by id. */
    public synchronized Map<Long, Long> cosmeticOwnerCounts() throws SQLException {
        Map<Long, Long> counts = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT cosmetic_id, COUNT(*) FROM cosmetic_owned GROUP BY cosmetic_id")) {
            while (rs.next()) {
                counts.put(rs.getLong(1), rs.getLong(2));
            }
        }
        return counts;
    }

    public synchronized List<OwnedCosmetic> ownedCosmetics(UUID uuid) throws SQLException {
        return readOwned("SELECT * FROM cosmetic_owned WHERE minecraft_uuid = ? ORDER BY acquired_at", uuid.toString());
    }

    /** Every cosmetic in use, by anyone. */
    public synchronized List<OwnedCosmetic> equippedCosmetics() throws SQLException {
        return readOwned("SELECT * FROM cosmetic_owned WHERE equipped_at IS NOT NULL", null);
    }

    private List<OwnedCosmetic> readOwned(String sql, String param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            List<OwnedCosmetic> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long equipped = rs.getLong("equipped_at");
                    boolean isEquipped = !rs.wasNull();
                    list.add(new OwnedCosmetic(UUID.fromString(rs.getString("minecraft_uuid")), rs.getLong("cosmetic_id"),
                            rs.getLong("paid"), Instant.ofEpochMilli(rs.getLong("acquired_at")),
                            isEquipped ? Instant.ofEpochMilli(equipped) : null, rs.getString("style")));
                }
            }
            return list;
        }
    }

    /**
     * Buys a cosmetic: takes the credits and adds it to what the player has, in one go.
     *
     * @return the new balance, -1 if they don't have enough credits, -2 if they already have it
     */
    public synchronized long buyCosmetic(UUID uuid, String name, Cosmetic cosmetic, String detail, String actor)
            throws SQLException {
        return transaction(() -> {
            if (ownsCosmetic(uuid, cosmetic.id())) {
                return -2L;
            }
            ensureCreditAccount(uuid, name);
            long balance = creditBalance(uuid) - cosmetic.price();
            if (balance < 0) {
                return -1L;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fad_accounts SET credits = credits - ? WHERE minecraft_uuid = ?")) {
                ps.setLong(1, cosmetic.price());
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            insertCreditTransaction(uuid, -cosmetic.price(), balance, "COSMETIC", detail, actor, Instant.now());
            insertOwned(uuid, cosmetic.id(), cosmetic.price());
            return balance;
        });
    }

    /** Sets the look a player picked for a trail or aura they have (null = the item's own); false if they don't have it. */
    public synchronized boolean setOwnedStyle(UUID uuid, long cosmeticId, String style) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE cosmetic_owned SET style = ? WHERE minecraft_uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, style);
            ps.setString(2, uuid.toString());
            ps.setLong(3, cosmeticId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Gives a cosmetic for free; false if they already have it. */
    public synchronized boolean giveCosmetic(UUID uuid, long cosmeticId) throws SQLException {
        if (ownsCosmetic(uuid, cosmeticId)) {
            return false;
        }
        insertOwned(uuid, cosmeticId, 0);
        return true;
    }

    /** Takes a cosmetic away (no refund); false if they didn't have it. */
    public synchronized boolean revokeCosmetic(UUID uuid, long cosmeticId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM cosmetic_owned WHERE minecraft_uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, cosmeticId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Puts a cosmetic to use, or stops using it. Only one of each kind is used at a time, so using one stops the
     * others of its kind. Returns false if the player doesn't have it.
     */
    public synchronized boolean setCosmeticEquipped(UUID uuid, long cosmeticId, String kind, boolean on)
            throws SQLException {
        return transaction(() -> {
            if (!ownsCosmetic(uuid, cosmeticId)) {
                return false;
            }
            if (on) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE cosmetic_owned SET equipped_at = NULL WHERE minecraft_uuid = ? AND cosmetic_id <> ? "
                                + "AND cosmetic_id IN (SELECT id FROM cosmetics WHERE kind = ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, cosmeticId);
                    ps.setString(3, kind);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(on
                    ? "UPDATE cosmetic_owned SET equipped_at = COALESCE(equipped_at, ?) WHERE minecraft_uuid = ? AND cosmetic_id = ?"
                    : "UPDATE cosmetic_owned SET equipped_at = NULL WHERE minecraft_uuid = ? AND cosmetic_id = ?")) {
                int i = 1;
                if (on) {
                    ps.setLong(i++, Instant.now().toEpochMilli());
                }
                ps.setString(i++, uuid.toString());
                ps.setLong(i, cosmeticId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    private boolean ownsCosmetic(UUID uuid, long cosmeticId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM cosmetic_owned WHERE minecraft_uuid = ? AND cosmetic_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, cosmeticId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertOwned(UUID uuid, long cosmeticId, long paid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cosmetic_owned (minecraft_uuid, cosmetic_id, paid, acquired_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, cosmeticId);
            ps.setLong(3, paid);
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
    }

    // ---- Discord message log ----

    /** {@code channelName} is kept so the log still has a name for channels that were deleted. */
    public synchronized void addDiscordMessage(DiscordMessage m, String channelName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO discord_messages (message_id, channel_id, author_id, author_name, author_avatar, bot, "
                        + "content, attachments, created_at, channel_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, m.id());
            ps.setLong(2, m.channelId());
            ps.setLong(3, m.authorId());
            ps.setString(4, m.authorName());
            ps.setString(5, m.authorAvatar());
            ps.setInt(6, m.bot() ? 1 : 0);
            ps.setString(7, m.content());
            ps.setString(8, m.attachments());
            ps.setLong(9, m.createdAt());
            ps.setString(10, channelName);
            ps.executeUpdate();
        }
    }

    /** An edit: the new text replaces the old, and the first version is kept as {@code original}. */
    public synchronized void editDiscordMessage(long id, String content, long editedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE discord_messages SET original = COALESCE(original, content), content = ?, edited_at = ? "
                        + "WHERE message_id = ? AND content <> ?")) {
            ps.setString(1, content);
            ps.setLong(2, editedAt);
            ps.setLong(3, id);
            ps.setString(4, content);
            ps.executeUpdate();
        }
    }

    /** Deleted messages stay in the log, marked as deleted. */
    public synchronized void deleteDiscordMessages(Collection<Long> ids, long deletedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE discord_messages SET deleted_at = ? WHERE message_id = ? AND deleted_at IS NULL")) {
            for (long id : ids) {
                ps.setLong(1, deletedAt);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** The name each logged channel had at its latest saved message. */
    public synchronized Map<Long, String> discordChannelNames() throws SQLException {
        Map<Long, String> names = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT channel_id, channel_name, MAX(created_at) FROM discord_messages "
                     + "WHERE channel_name IS NOT NULL GROUP BY channel_id")) {
            while (rs.next()) {
                names.put(rs.getLong(1), rs.getString(2));
            }
        }
        return names;
    }

    /** Messages per channel and the latest one's time: channel id -> {count, latest}. */
    public synchronized Map<Long, long[]> discordMessageCounts() throws SQLException {
        Map<Long, long[]> counts = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT channel_id, COUNT(*), MAX(created_at) FROM discord_messages GROUP BY channel_id")) {
            while (rs.next()) {
                counts.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        }
        return counts;
    }

    /**
     * A channel's messages, newest first: the {@code limit} before message {@code before} (null = the newest), and
     * only those containing {@code search} (in the text, the author's name or an edit's first version) if given.
     */
    public synchronized List<DiscordMessage> discordMessages(long channelId, Long before, String search, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM discord_messages WHERE channel_id = ?");
        if (before != null) {
            sql.append(" AND message_id < ?");
        }
        if (search != null) {
            sql.append(" AND (content LIKE ? ESCAPE '\\' OR author_name LIKE ? ESCAPE '\\' OR original LIKE ? ESCAPE '\\')");
        }
        sql.append(" ORDER BY message_id DESC LIMIT ?");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setLong(i++, channelId);
            if (before != null) {
                ps.setLong(i++, before);
            }
            if (search != null) {
                String like = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
            }
            ps.setInt(i, limit);
            List<DiscordMessage> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long edited = rs.getLong("edited_at");
                    boolean wasEdited = !rs.wasNull();
                    long deleted = rs.getLong("deleted_at");
                    boolean wasDeleted = !rs.wasNull();
                    list.add(new DiscordMessage(rs.getLong("message_id"), rs.getLong("channel_id"), rs.getLong("author_id"),
                            rs.getString("author_name"), rs.getString("author_avatar"), rs.getInt("bot") != 0,
                            rs.getString("content"), rs.getString("attachments"), rs.getLong("created_at"),
                            wasEdited ? edited : null, rs.getString("original"), wasDeleted ? deleted : null));
                }
            }
            return list;
        }
    }

    public synchronized int deleteDiscordMessagesBefore(Instant before) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM discord_messages WHERE created_at < ?")) {
            ps.setLong(1, before.toEpochMilli());
            return ps.executeUpdate();
        }
    }

    // ---- Market: microcredits from selling items ----

    /**
     * Adds (or, negative, takes) microcredits; returns the new balance, or -1 and changes nothing if that would go
     * below 0. A positive amount adds to "earned in total" when {@code countsAsEarned}.
     */
    public synchronized long addMicro(UUID uuid, String name, long amount, String kind, String detail, String actor,
                                      boolean countsAsEarned) throws SQLException {
        return transaction(() -> changeMicro(uuid, name, amount, kind, detail, actor, countsAsEarned));
    }

    private long changeMicro(UUID uuid, String name, long amount, String kind, String detail, String actor,
                             boolean countsAsEarned) throws SQLException {
        ensureCreditAccount(uuid, name);
        long balance = microBalance(uuid) + amount;
        if (balance < 0) {
            return -1L;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fad_accounts SET micro = micro + ?, micro_earned = micro_earned + ? WHERE minecraft_uuid = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, countsAsEarned ? Math.max(0, amount) : 0);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO micro_transactions (minecraft_uuid, amount, balance, kind, detail, actor, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, amount);
            ps.setLong(3, balance);
            ps.setString(4, kind);
            ps.setString(5, detail);
            ps.setString(6, actor);
            ps.setLong(7, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
        return balance;
    }

    private long microBalance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT micro FROM fad_accounts WHERE minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** After an exchange: the new balances, or null (nothing changed) if there weren't enough microcredits. */
    public record Exchange(long micro, long credits) {
    }

    /** Turns {@code micro} microcredits into {@code credits} credits, in one go. */
    public synchronized Exchange exchangeMicro(UUID uuid, String name, long micro, long credits, String detail,
                                               String actor) throws SQLException {
        return transaction(() -> {
            long microLeft = changeMicro(uuid, name, -micro, "EXCHANGE", detail, actor, false);
            if (microLeft < 0) {
                return null;
            }
            long balance = creditBalance(uuid) + credits;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fad_accounts SET credits = credits + ? WHERE minecraft_uuid = ?")) {
                ps.setLong(1, credits);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            insertCreditTransaction(uuid, credits, balance, "EXCHANGE", detail, actor, Instant.now());
            return new Exchange(microLeft, balance);
        });
    }

    /** 1 for the player with the most microcredits (or most earned, with {@code earned}); 0 without an account. */
    public synchronized long microRank(UUID uuid, boolean earned) throws SQLException {
        String column = earned ? "micro_earned" : "micro";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT CASE WHEN a." + column + " > 0 THEN 1 + (SELECT COUNT(*) FROM fad_accounts o WHERE o." + column
                        + " > a." + column + ") ELSE 0 END FROM fad_accounts a WHERE a.minecraft_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public synchronized List<CreditTransaction> microTransactions(UUID uuid, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM micro_transactions WHERE minecraft_uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            List<CreditTransaction> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new CreditTransaction(rs.getLong("id"), rs.getLong("amount"), rs.getLong("balance"),
                            rs.getString("kind"), rs.getString("detail"), rs.getString("actor"),
                            Instant.ofEpochMilli(rs.getLong("created_at"))));
                }
            }
            return list;
        }
    }

    /** One player's sales over a period. */
    public record SalesRow(UUID uuid, String name, long amount, long sales) {
    }

    /** Who sold the most between {@code from} and {@code to}. */
    public synchronized List<SalesRow> topSellers(Instant from, Instant to, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT t.minecraft_uuid, a.minecraft_name, SUM(t.amount) AS total, COUNT(*) AS sales "
                        + "FROM micro_transactions t JOIN fad_accounts a ON a.minecraft_uuid = t.minecraft_uuid "
                        + "WHERE t.kind = 'SALE' AND t.created_at >= ? AND t.created_at < ? "
                        + "GROUP BY t.minecraft_uuid ORDER BY total DESC LIMIT ?")) {
            ps.setLong(1, from.toEpochMilli());
            ps.setLong(2, to.toEpochMilli());
            ps.setInt(3, limit);
            List<SalesRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SalesRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4)));
                }
            }
            return rows;
        }
    }

    /** How much of an item was sold lately, as stored: thousandths of a credit's worth, as of {@code updatedAt}. */
    public record MarketPressure(double pressure, long updatedAt) {
    }

    public synchronized Map<String, MarketPressure> marketPressure() throws SQLException {
        Map<String, MarketPressure> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM market_items")) {
            while (rs.next()) {
                map.put(rs.getString("item"), new MarketPressure(rs.getDouble("pressure"), rs.getLong("updated_at")));
            }
        }
        return map;
    }

    public synchronized void setMarketPressure(Map<String, MarketPressure> changed) throws SQLException {
        transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO market_items (item, pressure, updated_at) VALUES (?, ?, ?) ON CONFLICT (item) "
                            + "DO UPDATE SET pressure = excluded.pressure, updated_at = excluded.updated_at")) {
                for (Map.Entry<String, MarketPressure> entry : changed.entrySet()) {
                    ps.setString(1, entry.getKey());
                    ps.setDouble(2, entry.getValue().pressure());
                    ps.setLong(3, entry.getValue().updatedAt());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public synchronized Optional<String> marketState(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM market_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized void setMarketState(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO market_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    /** Runs {@code work} as one transaction: all of it is saved, or none of it. */
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // ---- Mapping ----

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Optional<LinkedAccount> findOne(String sql, Object param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readAccount(rs)) : Optional.empty();
            }
        }
    }

    private static LinkedAccount readAccount(ResultSet rs) throws SQLException {
        long lastSeen = rs.getLong("last_seen");
        boolean neverSeen = rs.wasNull();
        return new LinkedAccount(
                UUID.fromString(rs.getString("minecraft_uuid")),
                rs.getString("minecraft_name"),
                rs.getLong("discord_id"),
                Instant.ofEpochMilli(rs.getLong("linked_at")),
                rs.getString("discord_name"),
                rs.getString("discord_avatar"),
                neverSeen ? null : Instant.ofEpochMilli(lastSeen));
    }

    private static Suspension readSuspension(ResultSet rs) throws SQLException {
        return new Suspension(
                rs.getLong("id"),
                UUID.fromString(rs.getString("minecraft_uuid")),
                rs.getString("reason"),
                rs.getString("created_by"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                rs.getObject("lifted_at") == null ? null : Instant.ofEpochMilli(rs.getLong("lifted_at")),
                rs.getString("lifted_by"),
                rs.getString("lift_reason"));
    }

    private static List<AuditEntry> readEntries(PreparedStatement ps) throws SQLException {
        List<AuditEntry> entries = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String uuid = rs.getString("minecraft_uuid");
                long discordId = rs.getLong("discord_id");
                boolean noDiscordId = rs.wasNull();
                entries.add(new AuditEntry(
                        rs.getLong("id"),
                        Instant.ofEpochMilli(rs.getLong("timestamp")),
                        uuid == null ? null : UUID.fromString(uuid),
                        rs.getString("minecraft_name"),
                        noDiscordId ? null : discordId,
                        rs.getString("event_type"),
                        rs.getString("detail"),
                        rs.getString("actor")));
            }
        }
        return entries;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
