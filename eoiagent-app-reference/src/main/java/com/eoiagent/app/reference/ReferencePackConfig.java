package com.eoiagent.app.reference;

import com.eoiagent.app.PackConfig;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.Map;

/**
 * OFFLINE pack configuration. {@code featureOverrides()} only <em>restrict</em> within the OFFLINE
 * capability matrix (mutating actions and MCP tools off — never enabling what the profile forbids),
 * and {@code configDefaults()} ship the {@code eoiagent.*} OFFLINE defaults a host may override.
 */
final class ReferencePackConfig implements PackConfig {

    @Override
    public DeploymentProfile profile() {
        return DeploymentProfile.OFFLINE;
    }

    @Override
    public Map<Feature, Boolean> featureOverrides() {
        return Map.of(Feature.MUTATING_ACTIONS, false, Feature.MCP_TOOLS, false);
    }

    @Override
    public Map<String, String> configDefaults() {
        return Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.model.chat.provider", "ollama",
                "eoiagent.model.chat.baseUrl", "http://localhost:11434/v1",
                "eoiagent.model.chat.modelId", "qwen2.5:14b-instruct",
                "eoiagent.model.embedding.provider", "onnx-all-minilm",
                "eoiagent.vectorstore.kind", "in-memory",
                "eoiagent.host.navigation.preferOverInline", "true");
    }
}
