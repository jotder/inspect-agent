package com.eoiagent.knowledge;

import com.eoiagent.core.Citation;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Embeds the query in-JVM and runs top-k similarity search, mapping each hit to a
 * {@link RetrievedChunk} with a {@link Citation} so answers stay traceable. Read-only, offline.
 */
public final class DefaultRetriever implements Retriever {

    private final EmbeddingModel embeddingModel;
    private final VectorStore store;

    public DefaultRetriever(EmbeddingModel embeddingModel, VectorStore store) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("query text must be non-blank");
        }
        if (query.k() < 1) {
            throw new IllegalArgumentException("query k must be >= 1");
        }
        float[] vector = embeddingModel.embed(query.text()).content().vector();
        MetadataFilter filter = query.filter() == null ? MetadataFilter.none() : query.filter();
        return store.search(vector, query.k(), filter).stream()
                .map(DefaultRetriever::toRetrievedChunk)
                .toList();
    }

    private static RetrievedChunk toRetrievedChunk(Match match) {
        Map<String, String> md = match.chunk().metadata();
        Citation citation = new Citation(
                md.getOrDefault("sourceId", ""),
                md.getOrDefault("title", ""),
                md.getOrDefault("locator", ""));
        return new RetrievedChunk(match.chunk().text(), match.score(), citation);
    }
}
