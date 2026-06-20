package com.eoiagent.config;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * The {@code Feature × DeploymentProfile} table from
 * {@code docs/architecture/03-deployment-profiles.md}, encoded verbatim. {@link #permits} is the
 * hard ceiling: a ✗ cell can never be loosened by config. Only {@link Feature#HOSTED_MODELS} is
 * gated off, and only for {@code OFFLINE} / {@code ON_PREM_HOSTED} (hosted = internet egress).
 */
final class CapabilityMatrix {

    private static final Map<DeploymentProfile, EnumSet<Feature>> PERMITS = build();

    private CapabilityMatrix() {
    }

    static boolean permits(DeploymentProfile profile, Feature feature) {
        return PERMITS.get(profile).contains(feature);
    }

    private static Map<DeploymentProfile, EnumSet<Feature>> build() {
        EnumSet<Feature> all = EnumSet.allOf(Feature.class);
        EnumSet<Feature> noHosted = EnumSet.complementOf(EnumSet.of(Feature.HOSTED_MODELS));

        Map<DeploymentProfile, EnumSet<Feature>> m = new EnumMap<>(DeploymentProfile.class);
        m.put(DeploymentProfile.OFFLINE, noHosted);
        m.put(DeploymentProfile.ON_PREM_HOSTED, noHosted);
        m.put(DeploymentProfile.CLOUD, all);
        return m;
    }
}
