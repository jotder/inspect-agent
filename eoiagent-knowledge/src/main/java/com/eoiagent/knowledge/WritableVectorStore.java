package com.eoiagent.knowledge;

/**
 * Adds source-scoped removal to the core {@link VectorStore} read/add port. The ingestor needs this
 * to replace a source's chunks idempotently (re-ingest = delete-then-add); pure readers depend only
 * on {@code VectorStore}. A knowledge-module SPI, not a core port — every concrete store
 * ({@link InMemoryVectorStore}, and the Phase-2 pgvector store) implements it.
 */
public interface WritableVectorStore extends VectorStore {

    /** Remove every chunk previously stored for {@code sourceId} (no-op if none). */
    void removeBySourceId(String sourceId);
}
