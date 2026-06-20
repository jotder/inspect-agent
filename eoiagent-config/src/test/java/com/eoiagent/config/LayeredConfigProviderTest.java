package com.eoiagent.config;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Precedence chain programmatic &gt; env &gt; properties &gt; default (config-profiles AC10). */
class LayeredConfigProviderTest {

    @Test
    void precedenceProgrammaticOverEnvOverPropertiesOverDefault() { // AC10
        Properties props = new Properties();
        props.setProperty("eoiagent.audit.sink", "fromProps");
        props.setProperty("eoiagent.model.chat.modelId", "fromProps-model");

        Map<String, String> env = Map.of(
                EnvConfigProvider.toEnvVar("eoiagent.audit.sink"), "fromEnv",
                EnvConfigProvider.toEnvVar("eoiagent.vectorstore.kind"), "fromEnv-vs");

        Map<String, String> programmatic = Map.of("eoiagent.audit.sink", "fromProg");

        LayeredConfigProvider cfg = LayeredConfigProvider.builder()
                .programmatic(programmatic) // highest
                .env(env)
                .properties(props)          // lowest before defaults
                .build();

        assertThat(cfg.get(ConfigKeys.AUDIT_SINK)).isEqualTo("fromProg");           // programmatic wins
        assertThat(cfg.get(ConfigKeys.VECTORSTORE_KIND)).isEqualTo("fromEnv-vs");    // only env has it
        assertThat(cfg.get(ConfigKeys.MODEL_CHAT_MODEL_ID)).isEqualTo("fromProps-model"); // only props
        assertThat(cfg.get(ConfigKeys.APPROVAL_REQUIRED)).isTrue();                  // no layer → default
    }

    @Test
    void profileResolvedFromLowerLayerWhenHigherLayersSilent() {
        Properties props = new Properties();
        props.setProperty("eoiagent.profile", "CLOUD");

        LayeredConfigProvider cfg = LayeredConfigProvider.builder()
                .programmatic(Map.of())
                .properties(props)
                .build();

        assertThat(cfg.profile()).isEqualTo(DeploymentProfile.CLOUD);
    }

    @Test
    void contradictionValidatedOnTheComposedView() {
        // profile OFFLINE from the programmatic layer + hosted chat provider from properties.
        Properties props = new Properties();
        props.setProperty("eoiagent.model.chat.provider", "anthropic");

        assertThatThrownBy(() -> LayeredConfigProvider.builder()
                .programmatic(Map.of("eoiagent.profile", "OFFLINE"))
                .properties(props)
                .build())
                .isInstanceOf(ConfigException.class);
    }
}
