package com.eoiagent.tool;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/** In-test {@link ConfigProvider}: a fixed profile and a single toggle for {@code MUTATING_ACTIONS}. */
final class FakeConfigProvider implements ConfigProvider {

    private final DeploymentProfile profile;
    private final boolean mutatingActions;

    FakeConfigProvider(DeploymentProfile profile, boolean mutatingActions) {
        this.profile = profile;
        this.mutatingActions = mutatingActions;
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
        return feature == Feature.MUTATING_ACTIONS && mutatingActions;
    }
}
