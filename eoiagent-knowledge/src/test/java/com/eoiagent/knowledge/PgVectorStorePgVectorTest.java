package com.eoiagent.knowledge;

import com.eoiagent.core.DeploymentProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgVectorStore end-to-end against a real PostgreSQL + pgvector (T-206; rag-knowledge AC3 order,
 * AC4 metadata filter, AC5 source replace via removeBySourceId). Connects through the PG JDBC driver
 * (langchain4j-pgvector), so it needs a pgvector-enabled Postgres but not Docker-in-JVM.
 *
 * <p>Opt-in / env-gated (same pattern as the other PG integration tests): skipped unless
 * {@code EOIAGENT_IT_PGVECTOR_URL} is set, so the default offline build stays fast and green. Bring
 * up a pgvector instance and run, e.g.:
 * {@code docker run -d -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eoiagent -p 5433:5432
 * pgvector/pgvector:pg16}, then
 * {@code EOIAGENT_IT_PGVECTOR_URL=jdbc:postgresql://localhost:5433/eoiagent mvn -pl eoiagent-knowledge -am test}.
 */
@EnabledIfEnvironmentVariable(named = "EOIAGENT_IT_PGVECTOR_URL", matches = ".+")
class PgVectorStorePgVectorTest {

    private PgVectorStore store;

    @BeforeEach
    void setUp() {
        // A unique table per test isolates state on the shared instance.
        String table = "emb_test_" + System.nanoTime();
        store = new PgVectorStore(new PgVectorStore.Settings(
                System.getenv("EOIAGENT_IT_PGVECTOR_URL"),
                System.getenv().getOrDefault("EOIAGENT_IT_PGVECTOR_USER", "postgres"),
                System.getenv().getOrDefault("EOIAGENT_IT_PGVECTOR_PASSWORD", "postgres"),
                table, 3),
                DeploymentProfile.ON_PREM_HOSTED, true);
    }

    // PgVectorEmbeddingStore stores chunk ids as UUIDs, so ids must be UUID strings.
    private static EmbeddedChunk chunk(String text, float[] vec, String sourceId, String sourceType) {
        return new EmbeddedChunk(UUID.randomUUID().toString(), text, vec,
                Map.of("sourceId", sourceId, "sourceType", sourceType));
    }

    @Test
    void addThenSearchRanksByDescendingSimilarity() { // AC3
        store.add(List.of(
                chunk("apple", new float[]{1, 0, 0}, "s1", "PRODUCT_DOC"),
                chunk("banana", new float[]{0, 1, 0}, "s2", "PRODUCT_DOC")));

        List<Match> matches = store.search(new float[]{1, 0, 0}, 2, MetadataFilter.none());

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).chunk().text()).isEqualTo("apple"); // closest to the query vector
        assertThat(matches.get(0).score()).isGreaterThanOrEqualTo(matches.get(1).score()); // descending
        assertThat(matches.get(0).chunk().metadata()).containsEntry("sourceId", "s1");
    }

    @Test
    void metadataFilterScopesResults() { // AC4
        store.add(List.of(
                chunk("apple", new float[]{1, 0, 0}, "s1", "PRODUCT_DOC"),
                chunk("schema", new float[]{1, 0, 0}, "s2", "SCHEMA_CONFIG")));

        List<Match> matches = store.search(
                new float[]{1, 0, 0}, 5, new MetadataFilter(Map.of("sourceType", "SCHEMA_CONFIG")));

        assertThat(matches).isNotEmpty();
        assertThat(matches).allSatisfy(m ->
                assertThat(m.chunk().metadata()).containsEntry("sourceType", "SCHEMA_CONFIG"));
    }

    @Test
    void removeBySourceIdDeletesOnlyThatSource() { // AC5 (idempotent source replace primitive)
        store.add(List.of(
                chunk("apple", new float[]{1, 0, 0}, "s1", "PRODUCT_DOC"),
                chunk("banana", new float[]{0, 1, 0}, "s2", "PRODUCT_DOC")));

        store.removeBySourceId("s1");

        List<Match> matches = store.search(new float[]{1, 0, 0}, 5, MetadataFilter.none());
        assertThat(matches).extracting(m -> m.chunk().metadata().get("sourceId")).containsExactly("s2");
    }
}
