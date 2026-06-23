package com.eoiagent.persistence;

import com.eoiagent.core.RunId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostgresCheckpointStore} against a real PostgreSQL: the shared {@link CheckpointStoreContractTest}
 * plus the durability guarantee that a run's checkpoints survive a "restart" (a fresh store instance over
 * the same database — T-302 / orchestration-runtime AC9 resume-after-restart).
 *
 * <p>Opt-in / env-gated: skipped unless {@code EOIAGENT_IT_PG_URL} is set, so the default offline
 * {@code mvn test} stays green. Run with: {@code EOIAGENT_IT_PG_URL=jdbc:postgresql://localhost:5432/eoiagent
 * EOIAGENT_IT_PG_USER=postgres EOIAGENT_IT_PG_PASSWORD=postgres mvn -pl eoiagent-persistence -am test}.
 */
@EnabledIfEnvironmentVariable(named = "EOIAGENT_IT_PG_URL", matches = ".+")
class PostgresCheckpointStoreTest extends CheckpointStoreContractTest {

    private static final String TABLE = "eoiagent_checkpoint_test";

    private DataSource ds;

    static DataSource pgDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv("EOIAGENT_IT_PG_URL"));
        ds.setUser(System.getenv().getOrDefault("EOIAGENT_IT_PG_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("EOIAGENT_IT_PG_PASSWORD", "postgres"));
        return ds;
    }

    @BeforeEach
    void resetSchema() throws Exception {
        ds = pgDataSource();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE);
        }
        new PostgresCheckpointStore(ds, TABLE).ensureSchema();
    }

    @Override
    protected CheckpointStore store() {
        return new PostgresCheckpointStore(ds, TABLE);
    }

    @Test
    void checkpointsSurviveAFreshStoreInstance() { // resume-after-restart: a new store sees prior runs
        RunId run = new RunId("resume-run");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store().save(run, new Checkpoint(run, "gatherSignals", "state".getBytes(StandardCharsets.UTF_8), at, 0));
        store().save(run, new Checkpoint(run, "hypothesize", "more".getBytes(StandardCharsets.UTF_8), at, 1));

        // A brand-new store over the same database — as after a JVM restart.
        CheckpointStore reopened = new PostgresCheckpointStore(ds, TABLE);
        assertThat(reopened.latest(run)).isPresent();
        assertThat(reopened.latest(run).orElseThrow().nodeId()).isEqualTo("hypothesize");
        assertThat(reopened.history(run)).extracting(Checkpoint::nodeId)
                .containsExactly("gatherSignals", "hypothesize");
    }
}
