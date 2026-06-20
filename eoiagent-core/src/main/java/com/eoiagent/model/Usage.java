package com.eoiagent.model;

/**
 * Token usage accounting for a model call.
 * Fields provisional — refined by the owning module spec.
 */
public record Usage(int inputTokens, int outputTokens, int totalTokens) {
}
