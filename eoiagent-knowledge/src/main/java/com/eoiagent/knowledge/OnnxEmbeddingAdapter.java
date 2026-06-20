package com.eoiagent.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * In-JVM, offline embeddings via ONNX {@code all-MiniLM-L6-v2} (384-dim). This is the platform's
 * default embedding model; per the RAG spec it reuses the LangChain4j {@link EmbeddingModel}
 * abstraction rather than defining a new port. Runs entirely in-process — no network (AC1).
 */
public final class OnnxEmbeddingAdapter implements EmbeddingModel {

    /** all-MiniLM-L6-v2 output dimensionality. */
    public static final int DIMENSION = 384;

    private final EmbeddingModel delegate;

    public OnnxEmbeddingAdapter() {
        this.delegate = new AllMiniLmL6V2EmbeddingModel();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        return delegate.embedAll(segments);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
