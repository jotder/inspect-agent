package com.eoiagent.observability;

import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcAuditSink against a real PostgreSQL (T-206; audit spec AC1 round-trip, AC2 append-only/order).
 *
 * <p>Opt-in / env-gated: skipped unless {@code EOIAGENT_IT_PG_URL} is set, so the default offline
 * {@code mvn test} stays green. To run against the local instance, set the env vars and build:
 * {@code EOIAGENT_IT_PG_URL=jdbc:postgresql://localhost:5432/eoiagent EOIAGENT_IT_PG_USER=postgres
 * EOIAGENT_IT_PG_PASSWORD=postgres mvn -pl eoiagent-observability -am test}.
 */
@EnabledIfEnvironmentVariable(named = "EOIAGENT_IT_PG_URL", matches = ".+")
class JdbcAuditSinkPostgresTest {

    private static final String TABLE = "eoiagent_audit_test";

    private DataSource dataSource;
    private JdbcAuditSink sink;

    static DataSource pgDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv("EOIAGENT_IT_PG_URL"));
        ds.setUser(System.getenv().getOrDefault("EOIAGENT_IT_PG_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("EOIAGENT_IT_PG_PASSWORD", "postgres"));
        return ds;
    }

    @BeforeEach
    void setUp() throws Exception {
        dataSource = pgDataSource();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE);
        }
        sink = new JdbcAuditSink(dataSource, TABLE);
        sink.ensureSchema();
    }

    private static AuditEvent event(Instant at, String run, AuditKind kind, String summary, Map<String, Object> details) {
        return new AuditEvent(at, new AppId("app"), new RunId(run), new SessionId("sess"),
                new UserId("user"), kind, summary, details);
    }

    @Test
    void recordRoundTripsAllFields() { // AC1
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG timestamptz precision
        sink.record(event(at, "r1", AuditKind.MUTATION, "mutation: runPipeline",
                Map.of("tool", "runPipeline", "ok", true)));

        Map<String, Object> row = singleRow();
        assertThat(((OffsetDateTime) row.get("at")).toInstant()).isEqualTo(at);
        assertThat(row.get("app_id")).isEqualTo("app");
        assertThat(row.get("run_id")).isEqualTo("r1");
        assertThat(row.get("session_id")).isEqualTo("sess");
        assertThat(row.get("user_id")).isEqualTo("user");
        assertThat(row.get("kind")).isEqualTo("MUTATION");
        assertThat(row.get("summary")).isEqualTo("mutation: runPipeline");
        assertThat((String) row.get("details")).contains("\"tool\"").contains("runPipeline").contains("\"ok\"");
    }

    @Test
    void insertsAreAppendOnlyAndOrderedBySeq() { // AC2
        sink.record(event(Instant.now(), "r1", AuditKind.MODEL_CALL, "first", Map.of()));
        sink.record(event(Instant.now(), "r1", AuditKind.TOOL_CALL, "second", Map.of()));
        List<String> afterTwo = summariesOrdered();

        sink.record(event(Instant.now(), "r1", AuditKind.DECISION, "third", Map.of()));
        List<String> afterThree = summariesOrdered();

        assertThat(afterTwo).containsExactly("first", "second");
        // Earlier rows are unchanged and the new one is appended last (monotonic seq) — append-only.
        assertThat(afterThree).containsExactly("first", "second", "third");
    }

    private Map<String, Object> singleRow() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT at, app_id, run_id, session_id, user_id, kind, summary,"
                     + " details::text AS details FROM " + TABLE + " ORDER BY seq")) {
            assertThat(rs.next()).isTrue();
            return Map.of(
                    "at", rs.getObject("at", OffsetDateTime.class),
                    "app_id", rs.getString("app_id"),
                    "run_id", rs.getString("run_id"),
                    "session_id", rs.getString("session_id"),
                    "user_id", rs.getString("user_id"),
                    "kind", rs.getString("kind"),
                    "summary", rs.getString("summary"),
                    "details", rs.getString("details"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> summariesOrdered() {
        List<String> summaries = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT summary FROM " + TABLE + " ORDER BY seq")) {
            while (rs.next()) {
                summaries.add(rs.getString("summary"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return summaries;
    }
}
