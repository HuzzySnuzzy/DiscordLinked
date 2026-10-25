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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite storage for account links and the audit log.
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
        }
    }

    public synchronized Optional<LinkedAccount> findByUuid(UUID uuid) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE minecraft_uuid = ?", uuid.toString());
    }

    public synchronized Optional<LinkedAccount> findByDiscordId(long discordId) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE discord_id = ?", discordId);
    }

    public synchronized Optional<LinkedAccount> findByName(String minecraftName) throws SQLException {
        return findOne("SELECT * FROM linked_accounts WHERE minecraft_name = ? COLLATE NOCASE", minecraftName);
    }

    public synchronized void link(UUID uuid, String minecraftName, long discordId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO linked_accounts (minecraft_uuid, minecraft_name, discord_id, linked_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, minecraftName);
            ps.setLong(3, discordId);
            ps.setLong(4, Instant.now().toEpochMilli());
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

    public synchronized void logEvent(UUID uuid, String minecraftName, Long discordId,
                                      String eventType, String detail) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_log (timestamp, minecraft_uuid, minecraft_name, discord_id, event_type, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
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
            ps.executeUpdate();
        }
    }

    public synchronized List<AuditEntry> recentEvents(UUID uuid, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT timestamp, minecraft_name, event_type, detail FROM audit_log "
                        + "WHERE minecraft_uuid = ? ORDER BY timestamp DESC, id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            List<AuditEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(
                            Instant.ofEpochMilli(rs.getLong("timestamp")),
                            rs.getString("minecraft_name"),
                            rs.getString("event_type"),
                            rs.getString("detail")));
                }
            }
            return entries;
        }
    }

    private Optional<LinkedAccount> findOne(String sql, Object param) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkedAccount(
                        UUID.fromString(rs.getString("minecraft_uuid")),
                        rs.getString("minecraft_name"),
                        rs.getLong("discord_id"),
                        Instant.ofEpochMilli(rs.getLong("linked_at"))));
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
