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
 *
 * <p>The underlying ONNX runtime + model weights are loaded once per JVM and shared by every
 * adapter instance (T-402): the LC4j model is stateless and thread-safe. Measured honestly, this
 * does NOT speed up multi-boot suites (warm re-loads were already cheap; boot cost is corpus
 * ingestion + the once-per-JVM first load) — the fix is that N platform boots in one JVM now hold
 * one native ONNX session instead of N.
 */
public final class OnnxEmbeddingAdapter implements EmbeddingModel {

    /** all-MiniLM-L6-v2 output dimensionality. */
    public static final int DIMENSION = 384;

    /** Loaded once per JVM, on first use (JLS lazy holder idiom). */
    private static final class SharedModel {
        static final EmbeddingModel INSTANCE = new AllMiniLmL6V2EmbeddingModel();
    }

    private final EmbeddingModel delegate;

    public OnnxEmbeddingAdapter() {
        this.delegate = SharedModel.INSTANCE;
    }

    /** Test seam: the process-wide shared delegate (identity-asserted in the perf test). */
    static EmbeddingModel sharedDelegate() {
        return SharedModel.INSTANCE;
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
