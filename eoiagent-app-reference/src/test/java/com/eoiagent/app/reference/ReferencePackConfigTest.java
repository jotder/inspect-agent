package com.eoiagent.app.reference;

import com.eoiagent.app.PackConfig;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T-116 AC7: OFFLINE profile, restrict-only feature overrides, and the shipped eoiagent.* defaults. */
class ReferencePackConfigTest {

    private final PackConfig config = new ReferencePackConfig();

    @Test
    void profileIsOffline() {
        assertThat(config.profile()).isEqualTo(DeploymentProfile.OFFLINE);
    }

    @Test
    void featureOverridesOnlyRestrictNeverEnableAForbiddenFeature() {
        Map<Feature, Boolean> overrides = config.featureOverrides();
        // Every override is a restriction (false); nothing here enables a feature the matrix forbids.
        assertThat(overrides.values()).allMatch(enabled -> enabled == Boolean.FALSE);
        assertThat(overrides).containsEntry(Feature.MUTATING_ACTIONS, false);
        assertThat(overrides).containsEntry(Feature.MCP_TOOLS, false);
        // HOSTED_MODELS (the only matrix-gated feature) is never enabled by an override.
        assertThat(overrides.get(Feature.HOSTED_MODELS)).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void shipsOfflineConfigDefaults() {
        Map<String, String> defaults = config.configDefaults();
        assertThat(defaults).containsEntry("eoiagent.profile", "OFFLINE");
        assertThat(defaults).containsEntry("eoiagent.model.chat.provider", "ollama");
        assertThat(defaults).containsEntry("eoiagent.model.embedding.provider", "onnx-all-minilm");
        assertThat(defaults).containsEntry("eoiagent.vectorstore.kind", "in-memory");
    }
}
