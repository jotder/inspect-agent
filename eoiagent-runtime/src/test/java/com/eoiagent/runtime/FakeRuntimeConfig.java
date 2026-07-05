package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/** In-test {@link ConfigProvider} with overridable maxSteps / offload threshold; OFFLINE, all features on. */
final class FakeRuntimeConfig implements ConfigProvider {

    private final int maxSteps;
    private final int offloadThresholdBytes;
    private final int maxWorkers;
    private int reflectionMaxRevisions = RuntimeConfigKeys.REFLECTION_MAX_REVISIONS.defaultValue();

    FakeRuntimeConfig(int maxSteps, int offloadThresholdBytes) {
        this(maxSteps, offloadThresholdBytes, 3);
    }

    FakeRuntimeConfig(int maxSteps, int offloadThresholdBytes, int maxWorkers) {
        this.maxSteps = maxSteps;
        this.offloadThresholdBytes = offloadThresholdBytes;
        this.maxWorkers = maxWorkers;
    }

    /** Overrides the reflection revision budget (T-500); returns this for fluent test setup. */
    FakeRuntimeConfig withReflectionMaxRevisions(int revisions) {
        this.reflectionMaxRevisions = revisions;
        return this;
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
        if (key.name().equals(RuntimeConfigKeys.SUPERVISOR_MAX_WORKERS.name())) {
            return (T) Integer.valueOf(maxWorkers);
        }
        if (key.name().equals(RuntimeConfigKeys.REFLECTION_MAX_REVISIONS.name())) {
            return (T) Integer.valueOf(reflectionMaxRevisions);
        }
        return key.defaultValue();
    }

    @Override
    public boolean featureEnabled(Feature feature) {
        return true;
    }
}
