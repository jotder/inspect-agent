package com.eoiagent.memory;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.SessionId;
import com.eoiagent.knowledge.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-305 / memory spec AC8: {@code remember} then {@code recall} returns the relevant fact
 * (cross-session, via the real {@link InMemoryVectorStore} + deterministic stub embeddings);
 * with {@code LONG_TERM_MEMORY} disabled both methods fail closed with {@link PolicyViolation}.
 */
class VectorLongTermMemoryTest {

    private static final SessionId INVESTIGATION = new SessionId("session-investigation");
    private static final SessionId CHAT = new SessionId("session-chat");

    private static VectorLongTermMemory memory(boolean enabled) {
        return new VectorLongTermMemory(new KeywordEmbeddingGateway(), new InMemoryVectorStore(),
                new FeatureToggleConfig(enabled));
    }

    @Test
    void rememberThenRecallReturnsTheRelevantFactFirst() { // AC8
        VectorLongTermMemory ltm = memory(true);
        ltm.remember(INVESTIGATION, new MemoryFact(INVESTIGATION,
                "the orders_daily pipeline failed after a schema drift", Instant.parse("2026-07-01T02:03:12Z"), Map.of()));
        ltm.remember(CHAT, new MemoryFact(CHAT,
                "the user prefers the dark theme", Instant.parse("2026-06-15T10:00:00Z"), Map.of()));

        List<MemoryFact> recalled = ltm.recall("why did the orders pipeline fail", 2);

        assertThat(recalled).hasSize(2);
        assertThat(recalled.get(0).text()).contains("orders_daily");
        assertThat(recalled.get(0).scope()).isEqualTo(INVESTIGATION); // cross-session recall keeps provenance
    }

    @Test
    void disabledFeatureFailsClosedOnBothOperations() { // AC8
        VectorLongTermMemory ltm = memory(false);
        MemoryFact fact = new MemoryFact(CHAT, "anything", Instant.now(), Map.of());

        assertThatThrownBy(() -> ltm.remember(CHAT, fact)).isInstanceOf(PolicyViolation.class);
        assertThatThrownBy(() -> ltm.recall("anything", 1)).isInstanceOf(PolicyViolation.class);
    }

    @Test
    void recallOnEmptyCorpusReturnsEmptyList() {
        assertThat(memory(true).recall("orders pipeline", 3)).isEmpty();
    }

    @Test
    void recallRejectsNonPositiveK() {
        assertThatIllegalArgumentException().isThrownBy(() -> memory(true).recall("q", 0));
    }

    @Test
    void factRoundTripsScopeTimestampAndUserMetadata() {
        VectorLongTermMemory ltm = memory(true);
        Instant at = Instant.parse("2026-07-01T02:03:12Z");
        ltm.remember(INVESTIGATION, new MemoryFact(INVESTIGATION,
                "schema drift on orders", at, Map.of("source", "incident:INC-2001")));

        MemoryFact recalled = ltm.recall("orders schema", 1).get(0);

        assertThat(recalled.scope()).isEqualTo(INVESTIGATION);
        assertThat(recalled.at()).isEqualTo(at);
        assertThat(recalled.meta()).containsEntry("source", "incident:INC-2001")
                .doesNotContainKeys("ltm.scope", "ltm.at"); // reserved transport keys stripped
    }

    @Test
    void equallyRelevantFactsComeBackNewestFirst() {
        VectorLongTermMemory ltm = memory(true);
        String sameText = "deploy changed the orders pipeline";
        ltm.remember(CHAT, new MemoryFact(CHAT, sameText, Instant.parse("2026-06-01T00:00:00Z"), Map.of("v", "old")));
        ltm.remember(CHAT, new MemoryFact(CHAT, sameText, Instant.parse("2026-07-01T00:00:00Z"), Map.of("v", "new")));

        List<MemoryFact> recalled = ltm.recall("orders pipeline deploy", 2);

        assertThat(recalled.get(0).meta()).containsEntry("v", "new"); // newest-as-tiebreak
    }

    /** In-test {@link ConfigProvider}: OFFLINE profile with a single LONG_TERM_MEMORY toggle. */
    private record FeatureToggleConfig(boolean longTermMemory) implements ConfigProvider {
        @Override
        public DeploymentProfile profile() {
            return DeploymentProfile.OFFLINE;
        }

        @Override
        public <T> T get(ConfigKey<T> key) {
            return key.defaultValue();
        }

        @Override
        public boolean featureEnabled(Feature feature) {
            return feature == Feature.LONG_TERM_MEMORY && longTermMemory;
        }
    }
}
