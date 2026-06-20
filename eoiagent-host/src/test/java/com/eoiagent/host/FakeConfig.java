package com.eoiagent.host;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/** In-test {@link ConfigProvider} fixed to a profile; returns key defaults, all features enabled. */
final class FakeConfig implements ConfigProvider {

    private final DeploymentProfile profile;

    FakeConfig(DeploymentProfile profile) {
        this.profile = profile;
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
        return true;
    }
}
