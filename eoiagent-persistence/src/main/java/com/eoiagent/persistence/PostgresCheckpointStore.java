package com.eoiagent.persistence;

import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.RunId;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, multi-node {@link CheckpointStore} backed by PostgreSQL (T-302). Each {@link #save} inserts
 * one immutable row into {@code eoiagent_checkpoint} — <strong>append-only</strong> (no update, no
 * delete), so a run's full history survives a JVM restart and a fresh store instance over the same
 * database sees every prior checkpoint. {@link #latest} returns the most recent checkpoint for a run
 * (highest {@code seq}, ties broken by insertion order), {@link #history} returns them oldest-to-newest
 * — together enabling resume-after-restart and time-travel/replay (orchestration-runtime spec, Flow E).
 *
 * <p>Uses only {@code java.sql}/{@code javax.sql}; the JDBC driver is provided by the host runtime.
 * Construct with a host-supplied {@link DataSource} and call {@link #ensureSchema()} once at wiring.
 * Safe for concurrent saves on distinct {@link RunId}s.
 */
public final class PostgresCheckpointStore implements CheckpointStore {

    public static final String DEFAULT_TABLE = "eoiagent_checkpoint";

    private final DataSource dataSource;
    private final String table;

    public PostgresCheckpointStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public PostgresCheckpointStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Creates the append-only checkpoint table and its per-run index if they do not exist. */
    public void ensureSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id BIGSERIAL PRIMARY KEY,"
                + "run_id TEXT NOT NULL,"
                + "node_id TEXT NOT NULL,"
                + "state BYTEA NOT NULL,"
                + "at TIMESTAMPTZ NOT NULL,"
                + "seq INT NOT NULL)";
        String index = "CREATE INDEX IF NOT EXISTS " + table + "_run_seq_idx ON " + table + " (run_id, seq, id)";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
            s.execute(index);
        } catch (SQLException e) {
            throw new EoiAgentException("failed to ensure checkpoint schema for table " + table, e);
        }
    }

    @Override
    public void save(RunId id, Checkpoint cp) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cp, "cp");
        String insert = "INSERT INTO " + table + " (run_id, node_id, state, at, seq) VALUES (?,?,?,?,?)";
        Instant at = cp.at() == null ? Instant.now() : cp.at();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setString(1, id.value());
            ps.setString(2, cp.nodeId());
            ps.setBytes(3, cp.state() == null ? new byte[0] : cp.state());
            ps.setObject(4, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            ps.setInt(5, cp.seq());
            ps.executeUpdate(); // append-only: each save is a new row, never an overwrite
        } catch (SQLException e) {
            throw new EoiAgentException("failed to save checkpoint for run " + id.value(), e);
        }
    }

    @Override
    public Optional<Checkpoint> latest(RunId id) {
        Objects.requireNonNull(id, "id");
        String query = "SELECT run_id, node_id, state, at, seq FROM " + table
                + " WHERE run_id = ? ORDER BY seq DESC, id DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new EoiAgentException("failed to read latest checkpoint for run " + id.value(), e);
        }
    }

    @Override
    public List<Checkpoint> history(RunId id) {
        Objects.requireNonNull(id, "id");
        String query = "SELECT run_id, node_id, state, at, seq FROM " + table
                + " WHERE run_id = ? ORDER BY seq ASC, id ASC";
        List<Checkpoint> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs)); // oldest -> newest
                }
            }
        } catch (SQLException e) {
            throw new EoiAgentException("failed to read checkpoint history for run " + id.value(), e);
        }
        return List.copyOf(out);
    }

    private static Checkpoint map(ResultSet rs) throws SQLException {
        return new Checkpoint(
                new RunId(rs.getString("run_id")),
                rs.getString("node_id"),
                rs.getBytes("state"),
                rs.getObject("at", OffsetDateTime.class).toInstant(),
                rs.getInt("seq"));
    }
}
