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

    /** A linked player, or any player that appears in the audit log (using their most recent name). */
    public synchronized Optional<PlayerRef> findPlayer(UUID uuid) throws SQLException {
        Optional<LinkedAccount> linked = findByUuid(uuid);
        if (linked.isPresent()) {
            return linked.map(PlayerRef::of);
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
        return new ArrayList<>(found.values());
    }

    /** When an unlinked player last connected (or tried to), or null. Linked players use last_seen. */
    public synchronized Instant lastActivity(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(timestamp) FROM audit_log WHERE minecraft_uuid = ? "
                        + "AND event_type IN ('JOIN', 'LEAVE', 'DENIED_UNLINKED', 'DENIED_SUSPENDED')")) {
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

    public synchronized void logEvent(UUID uuid, String minecraftName, Long discordId,
                                      String eventType, String detail, String actor) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (timestamp, minecraft_uuid, minecraft_name, discord_id, event_type, detail, actor) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, Instant.now().toEpochMilli());
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
