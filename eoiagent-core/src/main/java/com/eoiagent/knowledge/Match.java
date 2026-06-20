package com.eoiagent.knowledge;

/**
 * A vector-store hit pairing a chunk with its similarity score.
 * Fields provisional — refined by the owning module spec.
 */
public record Match(EmbeddedChunk chunk, double score) {
}
