package com.eoiagent.knowledge;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedded, offline {@link VectorStore} wrapping LangChain4j {@code InMemoryEmbeddingStore} with
 * disk save/load (so an offline install can ship a pre-built index). No network (rag-knowledge AC1).
 */
public final class InMemoryVectorStore implements WritableVectorStore {

    private final InMemoryEmbeddingStore<TextSegment> store;

    public InMemoryVectorStore() {
        this.store = new InMemoryEmbeddingStore<>();
    }

    private InMemoryVectorStore(InMemoryEmbeddingStore<TextSegment> store) {
        this.store = store;
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        for (EmbeddedChunk c : chunks) {
            TextSegment segment = TextSegment.from(c.text(), new Metadata(c.metadata()));
            store.add(c.id(), Embedding.from(c.vector()), segment);
        }
    }

    @Override
    public List<Match> search(float[] queryVector, int k, MetadataFilter filter) {
        var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(k);
        Filter f = toFilter(filter);
        if (f != null) {
            builder.filter(f);
        }
        EmbeddingSearchResult<TextSegment> result = store.search(builder.build());

        List<Match> matches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : result.matches()) {
            TextSegment segment = m.embedded();
            EmbeddedChunk chunk = new EmbeddedChunk(
                    m.embeddingId(), segment.text(), m.embedding().vector(),
                    toStringMap(segment.metadata().toMap()));
            matches.add(new Match(chunk, m.score()));
        }
        return matches;
    }

    @Override
    public void removeBySourceId(String sourceId) {
        store.removeAll(MetadataFilterBuilder.metadataKey("sourceId").isEqualTo(sourceId));
    }

    /** Persist the index to disk. */
    public void save(Path path) {
        store.serializeToFile(path);
    }

    /** Load an index previously written by {@link #save(Path)} into a fresh store. */
    public static InMemoryVectorStore load(Path path) {
        return new InMemoryVectorStore(InMemoryEmbeddingStore.fromFile(path));
    }

    public int size() {
        return store.size();
    }

    private static Filter toFilter(MetadataFilter filter) {
        if (filter == null || filter.constraints().isEmpty()) {
            return null;
        }
        Filter combined = null;
        for (Map.Entry<String, String> e : filter.constraints().entrySet()) {
            Filter clause = MetadataFilterBuilder.metadataKey(e.getKey()).isEqualTo(e.getValue());
            combined = (combined == null) ? clause : combined.and(clause);
        }
        return combined;
    }

    private static Map<String, String> toStringMap(Map<String, Object> map) {
        Map<String, String> out = new HashMap<>();
        map.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }
}
