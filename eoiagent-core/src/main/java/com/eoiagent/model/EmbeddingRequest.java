package com.eoiagent.model;

import java.util.List;

/** A request to embed one or more input strings. */
public record EmbeddingRequest(List<String> inputs) {
}
