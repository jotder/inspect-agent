package com.eoiagent.knowledge;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgVectorStore construction-time gating (rag-knowledge AC7/AC8). These never open a connection — the
 * checks throw before any DB access — so they run in the default offline build with no Docker/PG.
 */
class PgVectorStoreGatingTest {

    private static PgVectorStore.Settings settings(String jdbcUrl) {
        return new PgVectorStore.Settings(jdbcUrl, "postgres", "postgres", "eoiagent_embeddings", 384);
    }

    @Test
    void pgvectorFeatureDisabledThrowsConfigException() { // AC8
        assertThatThrownBy(() -> new PgVectorStore(
                settings("jdbc:postgresql://localhost:5432/eoiagent"), DeploymentProfile.ON_PREM_HOSTED, false))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("PGVECTOR");
    }

    @Test
    void offlineNonLocalUrlThrowsPolicyViolation() { // AC7
        assertThatThrownBy(() -> new PgVectorStore(
                settings("jdbc:postgresql://db.example.com:5432/eoiagent"), DeploymentProfile.OFFLINE, true))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("OFFLINE");
    }

    @Test
    void offlineGateIsCheckedBeforeAnyConnection() {
        // A non-local host offline must fail closed without attempting to connect (no exception type
        // other than PolicyViolation, e.g. no connection error, may surface first).
        assertThatThrownBy(() -> new PgVectorStore(
                settings("jdbc:postgresql://10.0.0.9:5432/eoiagent"), DeploymentProfile.OFFLINE, true))
                .isInstanceOf(PolicyViolation.class);
    }

    @Test
    void disabledFeatureIsCheckedBeforeUrlParsing() {
        // PGVECTOR-disabled fails closed even with a malformed URL — the feature gate comes first.
        assertThatCode(() -> {
            try {
                new PgVectorStore(settings("not-a-jdbc-url"), DeploymentProfile.ON_PREM_HOSTED, false);
            } catch (ConfigException expected) {
                if (!expected.getMessage().contains("PGVECTOR")) {
                    throw new AssertionError("expected the PGVECTOR feature gate, got: " + expected.getMessage());
                }
            }
        }).doesNotThrowAnyException();
    }
}
