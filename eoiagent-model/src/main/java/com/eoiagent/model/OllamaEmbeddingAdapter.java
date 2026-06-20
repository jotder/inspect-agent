package com.eoiagent.model;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * Alternative embedding backend over a local Ollama server (JDK {@code HttpClient}). Implements the
 * LangChain4j {@link EmbeddingModel} abstraction, like {@code OnnxEmbeddingAdapter} — the default
 * embedding path stays ONNX/offline; this is used only when a deployment opts into Ollama embeddings.
 */
public final class OllamaEmbeddingAdapter implements EmbeddingModel {

    private final EmbeddingModel delegate;

    public OllamaEmbeddingAdapter(String baseUrl, String modelId) {
        this.delegate = OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return delegate.embedAll(segments);
    }
}
