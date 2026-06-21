package com.eoiagent.app.reference;

import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.SourceKind;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.knowledge.IngestOptions;

import java.util.List;
import java.util.Map;

// The pack's three bundled corpora — product docs, schema descriptors and pipeline configs. Each
// resolve() returns DocumentSources pointing at small sample files under src/main/resources/acme/;
// the core DocumentIngestor loads/splits/embeds them. No dynamic data lives here — events/incidents
// come via tools, never RAG.

/** Product help text (PRODUCT_DOC). */
final class ProductDocSource implements KnowledgeSource {

    @Override
    public String id() {
        return "acme-docs";
    }

    @Override
    public SourceKind kind() {
        return SourceKind.PRODUCT_DOC;
    }

    @Override
    public IngestOptions options() {
        return IngestOptions.defaults();
    }

    @Override
    public List<DocumentSource> resolve() {
        return List.of(
                new DocumentSource("/acme/docs/overview.md", "text/markdown", Map.of("title", "Acme overview")),
                new DocumentSource("/acme/docs/kpi-help.md", "text/markdown", Map.of("title", "KPI dashboard help")));
    }
}

/** Dataset / data-model descriptors (SCHEMA_CONFIG). */
final class SchemaConfigSource implements KnowledgeSource {

    @Override
    public String id() {
        return "acme-schemas";
    }

    @Override
    public SourceKind kind() {
        return SourceKind.SCHEMA_CONFIG;
    }

    @Override
    public IngestOptions options() {
        return IngestOptions.defaults();
    }

    @Override
    public List<DocumentSource> resolve() {
        return List.of(
                new DocumentSource("/acme/schemas/orders.yaml", "application/yaml", Map.of("title", "orders schema")),
                new DocumentSource("/acme/schemas/customers.yaml", "application/yaml", Map.of("title", "customers schema")));
    }
}

/** ETL job configuration (CONFIG_FILE). */
final class PipelineConfigSource implements KnowledgeSource {

    @Override
    public String id() {
        return "acme-pipelines";
    }

    @Override
    public SourceKind kind() {
        return SourceKind.CONFIG_FILE;
    }

    @Override
    public IngestOptions options() {
        return IngestOptions.defaults();
    }

    @Override
    public List<DocumentSource> resolve() {
        return List.of(
                new DocumentSource("/acme/pipelines/nightly-load.yaml", "application/yaml",
                        Map.of("title", "nightly-load pipeline")));
    }
}
