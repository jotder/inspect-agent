package com.eoiagent.knowledge;

import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Top-k retrieval, metadata-filtered retrieval, citations, and empty-corpus behavior (T-104 AC1–AC3). */
class DefaultRetrieverTest {

    private final FixedEmbeddingModel embedder = new FixedEmbeddingModel(32);

    private EmbeddedChunk chunk(String id, String text, String sourceId, String sourceType) {
        return new EmbeddedChunk(id, text, embedder.embed(text).content().vector(),
                Map.of("sourceId", sourceId, "sourceType", sourceType, "title", sourceId, "locator", "chunk-0"));
    }

    private InMemoryVectorStore corpus() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(
                chunk("a", "orders table schema columns", "s-schema", "SCHEMA_CONFIG"),
                chunk("b", "ingestion pipeline failure handling", "s-pipe", "PIPELINE_CONFIG"),
                chunk("c", "product feature overview", "s-prod", "PRODUCT_DOC")));
        return store;
    }

    @Test
    void returnsAtMostKChunksOrderedByScoreWithCitations() { // AC1, AC3
        DefaultRetriever retriever = new DefaultRetriever(embedder, corpus());

        List<RetrievedChunk> chunks = retriever.retrieve(new RetrievalQuery("orders schema", 2, MetadataFilter.none()));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(RetrievedChunk::score)
                .isSortedAccordingTo((x, y) -> Double.compare(y, x));
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.citation()).isNotNull();
            assertThat(c.citation().sourceId()).isNotBlank();
            assertThat(c.citation().locator()).isNotBlank();
        });
    }

    @Test
    void honorsMetadataFilter() { // AC2
        DefaultRetriever retriever = new DefaultRetriever(embedder, corpus());

        List<RetrievedChunk> chunks = retriever.retrieve(new RetrievalQuery(
                "anything", 5, new MetadataFilter(Map.of("sourceType", "SCHEMA_CONFIG"))));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).citation().sourceId()).isEqualTo("s-schema");
    }

    @Test
    void emptyCorpusReturnsEmptyListWithoutThrowing() { // AC3
        DefaultRetriever retriever = new DefaultRetriever(embedder, new InMemoryVectorStore());
        assertThat(retriever.retrieve(new RetrievalQuery("anything", 5, MetadataFilter.none()))).isEmpty();
    }
}
