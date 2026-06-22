package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgresMemoryStore against a real PostgreSQL (T-206; memory spec AC3 round-trip + empty-on-unknown,
 * AC5 snapshot semantics, idempotent delete). {@code at} and {@code meta} round-trip in full.
 *
 * <p>Opt-in / env-gated: skipped unless {@code EOIAGENT_IT_PG_URL} is set, so the default offline
 * {@code mvn test} stays green. Run with: {@code EOIAGENT_IT_PG_URL=jdbc:postgresql://localhost:5432/eoiagent
 * EOIAGENT_IT_PG_USER=postgres EOIAGENT_IT_PG_PASSWORD=postgres mvn -pl eoiagent-memory -am test}.
 */
@EnabledIfEnvironmentVariable(named = "EOIAGENT_IT_PG_URL", matches = ".+")
class PostgresMemoryStoreTest {

    private static final String TABLE = "eoiagent_chat_memory_test";
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");

    private PostgresMemoryStore store;

    static DataSource pgDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv("EOIAGENT_IT_PG_URL"));
        ds.setUser(System.getenv().getOrDefault("EOIAGENT_IT_PG_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("EOIAGENT_IT_PG_PASSWORD", "postgres"));
        return ds;
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = pgDataSource();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + TABLE);
        }
        store = new PostgresMemoryStore(ds, TABLE);
        store.ensureSchema();
    }

    private static ChatMessageRecord msg(ChatRole role, String text, Map<String, String> meta) {
        return new ChatMessageRecord(role, text, Instant.now().truncatedTo(ChronoUnit.MICROS), meta);
    }

    @Test
    void putThenGetRoundTripsIncludingAtAndMeta() { // AC3
        List<ChatMessageRecord> messages = List.of(
                msg(ChatRole.USER, "hi", Map.of("lang", "en")),
                msg(ChatRole.ASSISTANT, "hello", Map.of("model", "stub", "tokens", "12")));

        store.put(S1, messages);

        assertThat(store.get(S1)).isEqualTo(messages);
    }

    @Test
    void getUnknownSessionReturnsEmptyNonNullList() { // AC3
        assertThat(store.get(new SessionId("nope"))).isNotNull().isEmpty();
    }

    @Test
    void putIsSnapshotNotAppend() { // AC5
        store.put(S1, List.of(
                msg(ChatRole.USER, "a", Map.of()), msg(ChatRole.ASSISTANT, "b", Map.of()),
                msg(ChatRole.USER, "c", Map.of()), msg(ChatRole.ASSISTANT, "d", Map.of()),
                msg(ChatRole.USER, "e", Map.of())));

        store.put(S1, List.of(msg(ChatRole.USER, "x", Map.of()), msg(ChatRole.ASSISTANT, "y", Map.of())));

        assertThat(store.get(S1)).hasSize(2);
        assertThat(store.get(S1)).extracting(ChatMessageRecord::text).containsExactly("x", "y");
    }

    @Test
    void distinctSessionsAreIsolated() {
        store.put(S1, List.of(msg(ChatRole.USER, "one", Map.of())));
        store.put(S2, List.of(msg(ChatRole.USER, "two", Map.of())));

        assertThat(store.get(S1)).extracting(ChatMessageRecord::text).containsExactly("one");
        assertThat(store.get(S2)).extracting(ChatMessageRecord::text).containsExactly("two");
    }

    @Test
    void deleteIsIdempotent() {
        store.put(S1, List.of(msg(ChatRole.USER, "hi", Map.of())));

        store.delete(S1);
        store.delete(S1); // no-op, no throw

        assertThat(store.get(S1)).isEmpty();
    }
}
