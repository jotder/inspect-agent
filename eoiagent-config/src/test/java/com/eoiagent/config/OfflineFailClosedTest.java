package com.eoiagent.config;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Feature;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The offline guarantees: hosted models unreachable, contradictions rejected (AC1, AC2, AC8, AC9). */
class OfflineFailClosedTest {

    private static ProgrammaticConfigProvider provider(Map<String, String> values) {
        return new ProgrammaticConfigProvider(values);
    }

    @Test
    void hostedModelsFalseOfflineEvenWhenEnabled() { // AC1
        ConfigProvider cfg = provider(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.features.hostedModels.enabled", "true"));
        assertThat(cfg.featureEnabled(Feature.HOSTED_MODELS)).isFalse();
    }

    @Test
    void hostedModelsAcrossProfiles() { // AC2
        assertThat(provider(Map.of("eoiagent.profile", "OFFLINE"))
                .featureEnabled(Feature.HOSTED_MODELS)).isFalse();
        assertThat(provider(Map.of("eoiagent.profile", "ON_PREM_HOSTED"))
                .featureEnabled(Feature.HOSTED_MODELS)).isFalse();
        assertThat(provider(Map.of(
                "eoiagent.profile", "CLOUD",
                "eoiagent.features.hostedModels.enabled", "true"))
                .featureEnabled(Feature.HOSTED_MODELS)).isTrue();
    }

    @Test
    void hostedChatProviderWhileOfflineThrowsAtConstruction() { // AC8
        assertThatThrownBy(() -> provider(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.model.chat.provider", "anthropic")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("hosted models");
    }

    @Test
    void hostedChatProviderWhileOnPremThrowsAtConstruction() { // AC8 (on-prem also ✗ hosted)
        assertThatThrownBy(() -> provider(Map.of(
                "eoiagent.profile", "ON_PREM_HOSTED",
                "eoiagent.model.chat.provider", "gemini")))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void localChatProviderWhileOfflineIsAllowed() {
        ConfigProvider cfg = provider(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.model.chat.provider", "openai-compatible"));
        assertThat(cfg.get(ConfigKeys.MODEL_CHAT_PROVIDER)).isEqualTo("openai-compatible");
    }

    @Test
    void mcpStdioAllowedOfflineWhenEnabled() { // AC9
        ConfigProvider cfg = provider(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.features.mcpTools.enabled", "true",
                "eoiagent.tools.mcp.transport", "stdio"));
        assertThat(cfg.featureEnabled(Feature.MCP_TOOLS)).isTrue();
    }

    @Test
    void remoteMcpTransportOfflineThrowsAtConstruction() { // AC9
        assertThatThrownBy(() -> provider(Map.of(
                "eoiagent.profile", "OFFLINE",
                "eoiagent.tools.mcp.transport", "http")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("stdio");
    }
}
