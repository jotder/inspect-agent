package com.eoiagent.examples;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.EnumSet;
import java.util.Set;

/**
 * A tiny demo-only {@link ConfigProvider}: a fixed {@link DeploymentProfile}, an explicit set of
 * enabled {@link Feature}s, and default values for every {@link ConfigKey}. The shipped providers
 * (eoiagent-config: {@code ProgrammaticConfigProvider}/{@code LayeredConfigProvider}) additionally
 * intersect features with the per-profile capability matrix; this shim just turns the named features
 * on so a demo can exercise a Phase-2 path without standing up that matrix.
 */
final class DemoConfig implements ConfigProvider {

    private final DeploymentProfile profile;
    private final Set<Feature> enabled;

    DemoConfig(DeploymentProfile profile, Feature... enabled) {
        this.profile = profile;
        this.enabled = enabled.length == 0 ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(Set.of(enabled));
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
        return enabled.contains(feature);
    }
}
