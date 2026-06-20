package com.eoiagent.config;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared contract every {@link ConfigProvider} adapter must satisfy. Each test runs against every
 * source kind via {@link Adapter}, so the three providers prove identical behavior.
 */
class ConfigProviderContractTest {

    /** Builds a provider whose source carries exactly the given dotted-key values. */
    enum Adapter {
        PROGRAMMATIC {
            @Override
            ConfigProvider build(Map<String, String> dotted) {
                return new ProgrammaticConfigProvider(dotted);
            }
        },
        PROPERTIES {
            @Override
            ConfigProvider build(Map<String, String> dotted) {
                Properties props = new Properties();
                props.putAll(dotted);
                return new PropertiesConfigProvider(props);
            }
        },
        ENV {
            @Override
            ConfigProvider build(Map<String, String> dotted) {
                Map<String, String> env = new HashMap<>();
                dotted.forEach((k, v) -> env.put(EnvConfigProvider.toEnvVar(k), v));
                return new EnvConfigProvider(env);
            }
        };

        abstract ConfigProvider build(Map<String, String> dotted);
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void missingProfileDefaultsToOffline(Adapter adapter) { // AC3
        assertThat(adapter.build(Map.of()).profile()).isEqualTo(DeploymentProfile.OFFLINE);
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void profileIsResolvedFromSource(Adapter adapter) {
        assertThat(adapter.build(Map.of("eoiagent.profile", "CLOUD")).profile())
                .isEqualTo(DeploymentProfile.CLOUD);
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void getReturnsSourceValueWhenPresent(Adapter adapter) { // AC5
        ConfigProvider cfg = adapter.build(Map.of("eoiagent.audit.sink", "jdbc"));
        assertThat(cfg.get(ConfigKeys.AUDIT_SINK)).isEqualTo("jdbc");
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void getReturnsDefaultWhenAbsent(Adapter adapter) { // AC5
        ConfigProvider cfg = adapter.build(Map.of());
        assertThat(cfg.get(ConfigKeys.APPROVAL_REQUIRED)).isTrue();
        assertThat(cfg.get(ConfigKeys.MODEL_EMBEDDING_PROVIDER)).isEqualTo("onnx-all-minilm");
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void getReturnsNullWhenNoSourceAndNoDefault(Adapter adapter) {
        assertThat(adapter.build(Map.of()).get(ConfigKeys.MODEL_CHAT_BASE_URL)).isNull();
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void booleanCoercion(Adapter adapter) { // AC5
        ConfigProvider cfg = adapter.build(Map.of("eoiagent.approval.required", "false"));
        assertThat(cfg.get(ConfigKeys.APPROVAL_REQUIRED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void featureMatrixGateHoldsRegardlessOfConfig(Adapter adapter) { // AC1
        ConfigProvider cfg = adapter.build(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.features.hostedModels.enabled", "true"));
        assertThat(cfg.featureEnabled(Feature.HOSTED_MODELS)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Adapter.class)
    void featureEnablingKeyCanRestrictWhenPermitted(Adapter adapter) {
        ConfigProvider on = adapter.build(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.features.pgvector.enabled", "true"));
        ConfigProvider off = adapter.build(Map.of("eoiagent.profile", "OFFLINE"));
        assertThat(on.featureEnabled(Feature.PGVECTOR)).isTrue();
        assertThat(off.featureEnabled(Feature.PGVECTOR)).isFalse(); // OFFLINE default off
    }
}
