package com.eoiagent.knowledge;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.IngestReport;
import com.eoiagent.core.IngestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Load → split → embed → store, with citations, idempotency, and corpus-type enforcement
 *  (T-103 AC1–AC3; rag-knowledge AC5, AC9). */
class DefaultDocumentIngestorTest {

    private static DocumentSource source(Path file, String id, String type, String title) {
        return new DocumentSource(file.toString(), "text/plain",
                Map.of("sourceId", id, "sourceType", type, "title", title));
    }

    private IngestRequest threeSources(Path tmp) throws IOException {
        Path doc = Files.writeString(tmp.resolve("product.txt"), "The dashboard shows pipeline health.");
        Path cfg = Files.writeString(tmp.resolve("pipeline.toon"), "pipeline: ingest\nsteps: [extract, load]");
        Path sch = Files.writeString(tmp.resolve("orders.schema"), "table orders { id long; total double }");
        return new IngestRequest(List.of(
                source(doc, "prod-1", "PRODUCT_DOC", "Product Help"),
                source(cfg, "pipe-1", "PIPELINE_CONFIG", "Ingest Pipeline"),
                source(sch, "schema-1", "SCHEMA_CONFIG", "Orders Schema")),
                null);
    }

    @Test
    void ingestsAllCorpusTypesAndCountsThem(@TempDir Path tmp) throws IOException { // AC1, AC2
        InMemoryVectorStore store = new InMemoryVectorStore();
        IngestReport report = new DefaultDocumentIngestor(new FixedEmbeddingModel(32), store)
                .ingest(threeSources(tmp));

        assertThat(report.documents()).isEqualTo(3);
        assertThat(report.chunks()).isGreaterThanOrEqualTo(3);
        assertThat(report.warnings()).isEmpty();
        assertThat(store.size()).isEqualTo(report.chunks());
    }

    @Test
    void chunksCarryCitationMetadata(@TempDir Path tmp) throws IOException { // AC3
        InMemoryVectorStore store = new InMemoryVectorStore();
        FixedEmbeddingModel embedder = new FixedEmbeddingModel(32);
        new DefaultDocumentIngestor(embedder, store).ingest(threeSources(tmp));

        float[] q = embedder.embed("anything").content().vector();
        Map<String, String> md = store.search(q, 10, MetadataFilter.none()).get(0).chunk().metadata();
        assertThat(md).containsKeys("sourceId", "sourceType", "title", "locator");
    }

    @Test
    void reIngestingSameSourceDoesNotDuplicate(@TempDir Path tmp) throws IOException { // AC5
        InMemoryVectorStore store = new InMemoryVectorStore();
        DefaultDocumentIngestor ingestor = new DefaultDocumentIngestor(new FixedEmbeddingModel(32), store);

        ingestor.ingest(threeSources(tmp));
        int afterFirst = store.size();
        ingestor.ingest(threeSources(tmp));

        assertThat(store.size()).isEqualTo(afterFirst);
    }

    @Test
    void rejectsDynamicDataSourceType(@TempDir Path tmp) throws IOException { // AC9
        Path file = Files.writeString(tmp.resolve("incident.json"), "{ id: 1 }");
        IngestRequest req = new IngestRequest(
                List.of(source(file, "inc-1", "INCIDENT", "Live Incident")), null);

        assertThatThrownBy(() -> new DefaultDocumentIngestor(new FixedEmbeddingModel(32), new InMemoryVectorStore())
                .ingest(req))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("corpus");
    }

    @Test
    void unreadableSourceBecomesAWarningNotAFailure(@TempDir Path tmp) {
        IngestRequest req = new IngestRequest(List.of(new DocumentSource(
                tmp.resolve("does-not-exist.txt").toString(), "text/plain",
                Map.of("sourceId", "missing-1", "sourceType", "PRODUCT_DOC"))), null);

        IngestReport report = new DefaultDocumentIngestor(new FixedEmbeddingModel(32), new InMemoryVectorStore())
                .ingest(req);

        assertThat(report.documents()).isZero();
        assertThat(report.warnings()).hasSize(1);
    }

    @Test
    void emptyRequestThrows() {
        assertThatThrownBy(() -> new DefaultDocumentIngestor(new FixedEmbeddingModel(32), new InMemoryVectorStore())
                .ingest(new IngestRequest(List.of(), null)))
                .isInstanceOf(ConfigException.class);
    }
}
