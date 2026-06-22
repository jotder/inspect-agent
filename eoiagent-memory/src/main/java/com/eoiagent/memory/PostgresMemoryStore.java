package com.eoiagent.memory;

import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.SessionId;
import dev.langchain4j.internal.Json;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable, multi-node {@link MemoryStore} backed by PostgreSQL (memory spec, Phase 2). Each session's
 * messages live as ordered rows in {@code eoiagent_chat_memory}; {@link #put} is
 * <strong>snapshot</strong> semantics (replace, not append): it deletes the session's rows and
 * re-inserts the supplied list in one transaction (last-write-wins). {@code at} and {@code meta} are
 * preserved in full (unlike the LC4j path — see {@link ChatMessageMapper}).
 *
 * <p>Uses only {@code java.sql}/{@code javax.sql} — the JDBC driver is provided by the host runtime.
 * Construct with a host-supplied {@link DataSource}; call {@link #ensureSchema()} once at wiring.
 * Safe for concurrent calls on distinct {@link SessionId}s; a single session is single-threaded from
 * the caller's view (memory spec / conventions §6).
 */
public final class PostgresMemoryStore implements MemoryStore {

    public static final String DEFAULT_TABLE = "eoiagent_chat_memory";

    private final DataSource dataSource;
    private final String table;

    public PostgresMemoryStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public PostgresMemoryStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Creates the chat-memory table if it does not exist. */
    public void ensureSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "session_id TEXT NOT NULL,"
                + "seq INT NOT NULL,"
                + "role TEXT NOT NULL,"
                + "text TEXT NOT NULL,"
                + "at TIMESTAMPTZ NOT NULL,"
                + "meta JSONB NOT NULL DEFAULT '{}'::jsonb,"
                + "PRIMARY KEY (session_id, seq))";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
        } catch (SQLException e) {
            throw new EoiAgentException("failed to ensure chat-memory schema for table " + table, e);
        }
    }

    @Override
    public void put(SessionId id, List<ChatMessageRecord> messages) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(messages, "messages");
        String delete = "DELETE FROM " + table + " WHERE session_id = ?";
        String insert = "INSERT INTO " + table
                + " (session_id, seq, role, text, at, meta) VALUES (?,?,?,?,?, CAST(? AS jsonb))";
        try (Connection c = dataSource.getConnection()) {
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement d = c.prepareStatement(delete)) {
                    d.setString(1, id.value());
                    d.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(insert)) {
                    int seq = 0;
                    for (ChatMessageRecord m : messages) {
                        Instant at = m.at() == null ? Instant.now() : m.at();
                        ins.setString(1, id.value());
                        ins.setInt(2, seq++);
                        ins.setString(3, m.role().name());
                        ins.setString(4, m.text() == null ? "" : m.text());
                        ins.setObject(5, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                        ins.setString(6, Json.toJson(m.meta() == null ? Map.of() : m.meta()));
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new EoiAgentException("failed to persist chat memory for session " + id.value(), e);
        }
    }

    @Override
    public List<ChatMessageRecord> get(SessionId id) {
        Objects.requireNonNull(id, "id");
        String query = "SELECT role, text, at, meta FROM " + table + " WHERE session_id = ? ORDER BY seq";
        List<ChatMessageRecord> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatRole role = ChatRole.valueOf(rs.getString("role"));
                    String text = rs.getString("text");
                    Instant at = rs.getObject("at", OffsetDateTime.class).toInstant();
                    out.add(new ChatMessageRecord(role, text, at, parseMeta(rs.getString("meta"))));
                }
            }
        } catch (SQLException e) {
            throw new EoiAgentException("failed to read chat memory for session " + id.value(), e);
        }
        return List.copyOf(out); // immutable snapshot; never null (empty for an unknown session)
    }

    @Override
    public void delete(SessionId id) {
        Objects.requireNonNull(id, "id");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE session_id = ?")) {
            ps.setString(1, id.value());
            ps.executeUpdate(); // idempotent: deleting an unknown session affects zero rows
        } catch (SQLException e) {
            throw new EoiAgentException("failed to delete chat memory for session " + id.value(), e);
        }
    }

    private static Map<String, String> parseMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<?, ?> raw = Json.fromJson(json, Map.class);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            meta.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return meta;
    }
}
