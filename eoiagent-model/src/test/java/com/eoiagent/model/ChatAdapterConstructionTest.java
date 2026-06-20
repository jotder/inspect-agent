package com.eoiagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real Ollama / OpenAI-compatible adapters construct offline (lazy — no connection until
 * chat/stream) and report local model info. Live calls are exercised only by opt-in integration
 * tests. (T-105 AC3, T-106 AC1/AC2.)
 */
class ChatAdapterConstructionTest {

    @Test
    void ollamaAdapterReportsLocalModel() { // T-105 AC3
        OllamaChatAdapter adapter = new OllamaChatAdapter("http://localhost:11434", "qwen2.5:14b-instruct");
        assertThat(adapter.activeChatModel().local()).isTrue();
        assertThat(adapter.activeChatModel().provider()).isEqualTo("ollama");
        assertThat(adapter.isAvailable(ModelRole.CHAT)).isTrue();
    }

    @Test
    void openAiCompatibleConstructsWithoutApiKeyAndTargetsBaseUrl() { // T-106 AC1
        OpenAiCompatibleChatAdapter adapter =
                new OpenAiCompatibleChatAdapter("http://localhost:8000/v1", "local-model", null);
        assertThat(adapter.activeChatModel().local()).isTrue();
        assertThat(adapter.activeChatModel().provider()).isEqualTo("openai-compatible");
    }

    @Test
    void nettyIsNotOnTheRuntimeClasspath() { // T-106 AC2 (JDK HttpClient transport, no Netty)
        assertThatThrownBy(() -> Class.forName("io.netty.channel.Channel"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
