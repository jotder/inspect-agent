package com.eoiagent.model;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

/**
 * Local Ollama chat backend over the JDK {@code HttpClient} transport (ADR-0002, no Netty). Always
 * reports {@code ModelInfo.local == true}. Construction is offline; only {@code chat}/{@code chatStream}
 * touch the server.
 */
public final class OllamaChatAdapter extends Lc4jChatGateway {

    public OllamaChatAdapter(String baseUrl, String modelId) {
        super(buildChat(baseUrl, modelId), buildStreaming(baseUrl, modelId),
                new ModelInfo("ollama", modelId, true));
    }

    private static ChatModel buildChat(String baseUrl, String modelId) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }

    private static StreamingChatModel buildStreaming(String baseUrl, String modelId) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
