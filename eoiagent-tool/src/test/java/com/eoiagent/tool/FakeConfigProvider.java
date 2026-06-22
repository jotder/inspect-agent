package com.eoiagent.tool;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/** In-test {@link ConfigProvider}: a fixed profile and toggles for {@code MUTATING_ACTIONS}/{@code MCP_TOOLS}. */
final class FakeConfigProvider implements ConfigProvider {

    private final DeploymentProfile profile;
    private final boolean mutatingActions;
    private final boolean mcpTools;

    FakeConfigProvider(DeploymentProfile profile, boolean mutatingActions) {
        this(profile, mutatingActions, false);
    }

    FakeConfigProvider(DeploymentProfile profile, boolean mutatingActions, boolean mcpTools) {
        this.profile = profile;
        this.mutatingActions = mutatingActions;
        this.mcpTools = mcpTools;
    }

    @Override
    public DeploymentProfile profile() {
        return profile;
    }

    @Override
    public <T> T get(ConfigKey<T> key) {
        return key.defaultValue();
    }

    @Override
    public boolean featureEnabled(Feature feature) {
        return switch (feature) {
            case MUTATING_ACTIONS -> mutatingActions;
            case MCP_TOOLS -> mcpTools;
            default -> false;
        };
    }
}
