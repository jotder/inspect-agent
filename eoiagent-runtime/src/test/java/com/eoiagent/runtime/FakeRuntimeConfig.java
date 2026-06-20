package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/** In-test {@link ConfigProvider} with overridable maxSteps / offload threshold; OFFLINE, all features on. */
final class FakeRuntimeConfig implements ConfigProvider {

    private final int maxSteps;
    private final int offloadThresholdBytes;

    FakeRuntimeConfig(int maxSteps, int offloadThresholdBytes) {
        this.maxSteps = maxSteps;
        this.offloadThresholdBytes = offloadThresholdBytes;
    }

    @Override
    public DeploymentProfile profile() {
        return DeploymentProfile.OFFLINE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(ConfigKey<T> key) {
        if (key.name().equals(RuntimeConfigKeys.MAX_STEPS.name())) {
            return (T) Integer.valueOf(maxSteps);
        }
        if (key.name().equals(RuntimeConfigKeys.OFFLOAD_THRESHOLD_BYTES.name())) {
            return (T) Integer.valueOf(offloadThresholdBytes);
        }
        return key.defaultValue();
    }

    @Override
    public boolean featureEnabled(Feature feature) {
        return true;
    }
}
