package com.eoiagent.knowledge;

/**
 * Options controlling chunking and idempotency during ingestion.
 * Fields provisional — refined by the owning module spec.
 */
public record IngestOptions(int maxChunkChars, int overlapChars, boolean idempotent) {

    public static IngestOptions defaults() {
        return new IngestOptions(1000, 200, true);
    }
}
