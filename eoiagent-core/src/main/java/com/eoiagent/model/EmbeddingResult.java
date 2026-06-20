package com.eoiagent.model;

import java.util.List;

/** The result of an embedding request: vectors and the model used. */
public record EmbeddingResult(List<float[]> vectors, ModelInfo model) {
}
