package com.eoiagent.observability;

import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * Insert-only {@link AuditSink} backing the append-only {@code eoiagent_audit} table (audit spec
 * §DDL). The compliance-grade store for {@code ON_PREM_HOSTED}/{@code CLOUD}. Each
 * {@link #record(AuditEvent)} is a single {@code INSERT}; this adapter exposes <strong>no</strong>
 * {@code UPDATE}/{@code DELETE} code path, and the monotonic {@code seq} identity column preserves
 * append order so a test can assert the C4 ordering (an {@code APPROVED} row precedes its
 * {@code MUTATION}). On a {@link SQLException} it throws {@link AuditException} so a mutating caller
 * fails closed (audit spec error handling).
 *
 * <p>Uses only {@code java.sql}/{@code javax.sql} — the JDBC driver is provided by the host runtime.
 * Construct with a host-supplied {@link DataSource}; call {@link #ensureSchema()} once at wiring to
 * create the table if absent.
 */
public final class JdbcAuditSink implements AuditSink {

    public static final String DEFAULT_TABLE = "eoiagent_audit";

    private final DataSource dataSource;
    private final String table;

    public JdbcAuditSink(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcAuditSink(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Creates the append-only audit table and its (run_id, seq) index if they do not exist. */
    public void ensureSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + "at TIMESTAMPTZ NOT NULL,"
                + "app_id TEXT NOT NULL,"
                + "run_id TEXT NOT NULL,"
                + "session_id TEXT NOT NULL,"
                + "user_id TEXT NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "summary TEXT NOT NULL,"
                + "details JSONB NOT NULL DEFAULT '{}'::jsonb)";
        String idx = "CREATE INDEX IF NOT EXISTS " + table + "_run_idx ON " + table + " (run_id, seq)";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
            s.execute(idx);
        } catch (SQLException e) {
            throw new AuditException("failed to ensure audit schema for table " + table, e);
        }
    }

    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        String sql = "INSERT INTO " + table
                + " (at, app_id, run_id, session_id, user_id, kind, summary, details)"
                + " VALUES (?,?,?,?,?,?,?, CAST(? AS jsonb))";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            Instant at = event.at() == null ? Instant.now() : event.at();
            ps.setObject(1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            ps.setString(2, id(event.app() == null ? null : event.app().value()));
            ps.setString(3, id(event.run() == null ? null : event.run().value()));
            ps.setString(4, id(event.session() == null ? null : event.session().value()));
            ps.setString(5, id(event.user() == null ? null : event.user().value()));
            ps.setString(6, event.kind() == null ? "" : event.kind().name());
            ps.setString(7, event.summary() == null ? "" : event.summary());
            ps.setString(8, toJson(event.details()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new AuditException("failed to insert audit event into " + table, e);
        }
    }

    private static String id(String value) {
        return value == null ? "" : value; // columns are NOT NULL; an absent id is the empty string
    }

    /** Minimal JSON object serialization for the details map (numbers/booleans bare, else quoted). */
    private static String toJson(Map<String, Object> details) {
        StringBuilder sb = new StringBuilder(64).append('{');
        if (details != null) {
            boolean first = true;
            for (Map.Entry<String, Object> e : details.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(escape(v.toString())).append('"');
                }
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
