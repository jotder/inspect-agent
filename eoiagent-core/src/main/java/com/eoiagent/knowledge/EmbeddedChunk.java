package com.eoiagent.knowledge;

import java.util.Map;

/**
 * A text chunk with its embedding vector and metadata.
 * Fields provisional — refined by the owning module spec.
 */
public record EmbeddedChunk(String id, String text, float[] vector, Map<String, String> metadata) {
}
