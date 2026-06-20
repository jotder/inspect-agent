package com.eoiagent.model;

/** Port abstracting chat and embedding model access. */
public interface LlmGateway {

    ChatResult chat(ChatRequest request);

    void chatStream(ChatRequest request, TokenSink sink);

    EmbeddingResult embed(EmbeddingRequest request);

    ModelInfo activeChatModel();

    boolean isAvailable(ModelRole role);
}
