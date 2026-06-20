package com.eoiagent.config;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Asserts every {@code Feature × DeploymentProfile} cell of the documented capability matrix. */
class CapabilityMatrixTest {

    @Test
    void hostedModelsPermittedOnlyForCloud() {
        assertThat(CapabilityMatrix.permits(DeploymentProfile.OFFLINE, Feature.HOSTED_MODELS)).isFalse();
        assertThat(CapabilityMatrix.permits(DeploymentProfile.ON_PREM_HOSTED, Feature.HOSTED_MODELS)).isFalse();
        assertThat(CapabilityMatrix.permits(DeploymentProfile.CLOUD, Feature.HOSTED_MODELS)).isTrue();
    }

    @Test
    void everyNonHostedFeaturePermittedInEveryProfile() {
        for (DeploymentProfile profile : DeploymentProfile.values()) {
            for (Feature feature : Feature.values()) {
                if (feature == Feature.HOSTED_MODELS) {
                    continue;
                }
                assertThat(CapabilityMatrix.permits(profile, feature))
                        .as("%s permits %s", profile, feature)
                        .isTrue();
            }
        }
    }
}
