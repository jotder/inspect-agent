package com.eoiagent.model;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * OpenAI-compatible chat backend (llama.cpp / vLLM / LM Studio / Ollama {@code /v1}) reached by
 * {@code baseUrl} over the JDK {@code HttpClient} transport (ADR-0002/ADR-0006, no Netty). No API key
 * is required for a local server — a placeholder is used when none is supplied. Treated as
 * {@code local} (a server reachable at a configured URL, not internet-hosted).
 */
public final class OpenAiCompatibleChatAdapter extends Lc4jChatGateway {

    public OpenAiCompatibleChatAdapter(String baseUrl, String modelId, String apiKey) {
        super(buildChat(baseUrl, modelId, apiKey), buildStreaming(baseUrl, modelId, apiKey),
                new ModelInfo("openai-compatible", modelId, true));
    }

    private static String keyOrPlaceholder(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? "not-needed" : apiKey;
    }

    private static ChatModel buildChat(String baseUrl, String modelId, String apiKey) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .apiKey(keyOrPlaceholder(apiKey))
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }

    private static StreamingChatModel buildStreaming(String baseUrl, String modelId, String apiKey) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .apiKey(keyOrPlaceholder(apiKey))
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
