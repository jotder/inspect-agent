package com.eoiagent.config;

import com.eoiagent.core.Feature;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * config-profiles AC7: {@code get}/{@code featureEnabled} read a snapshot taken at construction and
 * never re-consult a live source — the unit-level guarantee that call-time does no I/O. (The full
 * network-deny check lives in the CI harness.)
 */
class NoIoTest {

    @Test
    void programmaticSourceIsSnapshotted() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("eoiagent.profile", "OFFLINE");
        mutable.put("eoiagent.audit.sink", "file");

        ConfigProvider cfg = new ProgrammaticConfigProvider(mutable);

        // Mutating the original input after construction must not change resolved values.
        mutable.put("eoiagent.audit.sink", "jdbc");
        mutable.put("eoiagent.features.pgvector.enabled", "true");

        assertThat(cfg.get(ConfigKeys.AUDIT_SINK)).isEqualTo("file");
        assertThat(cfg.featureEnabled(Feature.PGVECTOR)).isFalse();
    }

    @Test
    void envSourceIsSnapshotted() {
        Map<String, String> mutableEnv = new HashMap<>();
        mutableEnv.put("EOIAGENT_PROFILE", "CLOUD");

        ConfigProvider cfg = new EnvConfigProvider(mutableEnv);
        mutableEnv.put("EOIAGENT_AUDIT_SINK", "jdbc");

        assertThat(cfg.get(ConfigKeys.AUDIT_SINK)).isNull(); // snapshot had no value → default (null)
    }

    @Test
    void repeatedCallsAreDeterministic() {
        ConfigProvider cfg = new ProgrammaticConfigProvider(Map.of("eoiagent.profile", "CLOUD"));
        for (int i = 0; i < 1000; i++) {
            assertThat(cfg.featureEnabled(Feature.HOSTED_MODELS)).isTrue();
            assertThat(cfg.featureEnabled(Feature.LANGGRAPH_CHECKPOINTING)).isFalse();
        }
    }
}
