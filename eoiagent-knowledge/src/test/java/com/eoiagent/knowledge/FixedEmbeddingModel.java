package com.eoiagent.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic, fast stub {@link EmbeddingModel} for ingestor/retriever logic tests — avoids
 * loading the real ONNX model. Same text → same vector. (ONNX itself is covered by
 * {@code OnnxEmbeddingAdapterTest}.)
 */
final class FixedEmbeddingModel implements EmbeddingModel {

    private final int dim;

    FixedEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> out = new ArrayList<>(segments.size());
        for (TextSegment s : segments) {
            out.add(Embedding.from(vector(s.text())));
        }
        return Response.from(out);
    }

    @Override
    public int dimension() {
        return dim;
    }

    private float[] vector(String text) {
        long seed = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            seed = 31 * seed + text.charAt(i);
        }
        Random r = new Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (r.nextDouble() * 2.0 - 1.0);
        }
        return v;
    }
}
