package com.eoiagent.config;

import com.eoiagent.core.DeploymentProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Env-var name mapping and reading (config-profiles AC6). */
class EnvConfigProviderTest {

    @Test
    void dottedKeyMapsToUpperSnakeEnvVar() { // AC6
        assertThat(EnvConfigProvider.toEnvVar("eoiagent.model.chat.provider"))
                .isEqualTo("EOIAGENT_MODEL_CHAT_PROVIDER");
        assertThat(EnvConfigProvider.toEnvVar("eoiagent.profile")).isEqualTo("EOIAGENT_PROFILE");
    }

    @Test
    void readsValueFromMappedEnvVar() { // AC6
        EnvConfigProvider cfg = new EnvConfigProvider(Map.of(
                "EOIAGENT_PROFILE", "CLOUD",
                "EOIAGENT_MODEL_CHAT_PROVIDER", "anthropic"));
        assertThat(cfg.profile()).isEqualTo(DeploymentProfile.CLOUD);
        assertThat(cfg.get(ConfigKeys.MODEL_CHAT_PROVIDER)).isEqualTo("anthropic");
    }
}
