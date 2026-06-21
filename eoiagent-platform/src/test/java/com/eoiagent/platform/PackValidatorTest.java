package com.eoiagent.platform;

import com.eoiagent.app.PageDescriptor;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-010 PackValidator: AC2 (PolicyViolation) and AC3 (ConfigException naming the missing provider). */
class PackValidatorTest {

    @Test
    void validOfflinePackPasses() {
        PackValidator.validate(new StubApplicationPack()); // no throw
    }

    @Test
    void nullPackFailsWithConfigException() {
        assertThatThrownBy(() -> PackValidator.validate(null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void missingRequiredProviderFailsWithConfigExceptionNamingIt() { // AC3
        StubApplicationPack pack = new StubApplicationPack().withToolProvider(null);
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("toolProvider");
    }

    @Test
    void missingNestedFieldFailsWithConfigExceptionNamingIt() { // AC3
        // A ModelProfile present but returning a null chat() selection must be named, not NPE.
        StubApplicationPack pack = new StubApplicationPack().withModelProfile(new com.eoiagent.app.ModelProfile() {
            @Override public com.eoiagent.app.ModelSelection chat() { return null; }
            @Override public com.eoiagent.app.ModelSelection embedding() {
                return new com.eoiagent.app.ModelSelection("onnx", "m", null, true);
            }
            @Override public com.eoiagent.app.RoutingPolicy routing() {
                return new com.eoiagent.app.RoutingPolicy(java.util.List.of(), false);
            }
        });
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("modelProfile.chat");
    }

    @Test
    void offlineWithHostedFallbackFailsWithPolicyViolation() { // AC2
        StubApplicationPack pack = new StubApplicationPack()
                .withModelProfile(StubApplicationPack.offlineModelProfile(true));
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("hosted fallback");
    }

    @Test
    void offlineFeatureOverrideEnablingHostedModelsFailsWithPolicyViolation() { // AC2
        StubApplicationPack pack = new StubApplicationPack().withConfig(
                StubApplicationPack.packConfig(DeploymentProfile.OFFLINE,
                        Map.of(Feature.HOSTED_MODELS, true), Map.of()));
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("HOSTED_MODELS");
    }

    @Test
    void restrictingFeatureOverrideIsAllowed() {
        // Turning a feature OFF is always permitted (the matrix is a ceiling, not a floor).
        StubApplicationPack pack = new StubApplicationPack().withConfig(
                StubApplicationPack.packConfig(DeploymentProfile.OFFLINE,
                        Map.of(Feature.MCP_TOOLS, false), Map.of()));
        PackValidator.validate(pack); // no throw
    }

    @Test
    void duplicatePageIdFailsWithConfigException() {
        PageDescriptor a = new PageDescriptor("p1", "Page One", "first", List.of());
        PageDescriptor dup = new PageDescriptor("p1", "Page One Again", "dup", List.of());
        StubApplicationPack pack = new StubApplicationPack().withPages(List.of(a, dup));
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("duplicate pageId");
    }

    @Test
    void cloudPackMayEnableHostedModelsAndFallback() {
        StubApplicationPack pack = new StubApplicationPack()
                .withModelProfile(StubApplicationPack.offlineModelProfile(true)) // fallback allowed under CLOUD
                .withConfig(StubApplicationPack.packConfig(DeploymentProfile.CLOUD,
                        Map.of(Feature.HOSTED_MODELS, true), Map.of()));
        PackValidator.validate(pack); // no throw — CLOUD permits hosted models
    }

    @Test
    void knowledgeSourcesWithNullElementFailsWithConfigException() {
        StubApplicationPack pack = new StubApplicationPack()
                .withKnowledgeSources(java.util.Arrays.asList((com.eoiagent.app.KnowledgeSource) null));
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("knowledgeSources");
    }

    @Test
    void appIdIsRequired() {
        StubApplicationPack pack = new StubApplicationPack()
                .withMetadata(new com.eoiagent.app.PackMetadata(null, "No Id", "1.0.0"));
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appId");
    }

    @Test
    void validatorReportsTheFirstMissingProviderField() {
        // Sanity: the message is specific enough to act on.
        StubApplicationPack pack = new StubApplicationPack().withPolicyProfile(null);
        assertThatThrownBy(() -> PackValidator.validate(pack))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("policyProfile"));
    }
}
