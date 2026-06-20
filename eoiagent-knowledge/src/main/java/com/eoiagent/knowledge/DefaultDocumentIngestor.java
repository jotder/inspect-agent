package com.eoiagent.knowledge;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.IngestReport;
import com.eoiagent.core.IngestRequest;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates load → split → embed → store. Picks a {@link DocumentLoader} by the source's declared
 * {@code sourceType}, splits with LangChain4j, embeds in-JVM, and stores chunks (carrying citation
 * metadata) idempotently per {@code sourceId}. A single unreadable source becomes a warning, not a
 * failure; misconfiguration (no sources, missing/invalid type) is a {@link ConfigException}.
 */
public final class DefaultDocumentIngestor implements DocumentIngestor {

    /** The corpus is static text only; dynamic operational data is served by tools, never RAG. */
    private static final Set<String> CORPUS_TYPES = Set.of("PRODUCT_DOC", "PIPELINE_CONFIG", "SCHEMA_CONFIG");

    private final EmbeddingModel embeddingModel;
    private final WritableVectorStore store;
    private final Map<String, DocumentLoader> loadersByType;
    private final int maxChunkChars;
    private final int overlapChars;

    public DefaultDocumentIngestor(EmbeddingModel embeddingModel, WritableVectorStore store) {
        this(embeddingModel, store,
                List.of(new ProductDocLoader(), new ConfigFileLoader(), new SchemaConfigLoader()),
                1000, 200);
    }

    public DefaultDocumentIngestor(EmbeddingModel embeddingModel, WritableVectorStore store,
                                   List<DocumentLoader> loaders, int maxChunkChars, int overlapChars) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.store = Objects.requireNonNull(store, "store");
        Map<String, DocumentLoader> byType = new HashMap<>();
        for (DocumentLoader loader : loaders) {
            byType.put(loader.sourceType(), loader);
        }
        this.loadersByType = byType;
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
    }

    @Override
    public IngestReport ingest(IngestRequest request) {
        if (request == null || request.sources() == null || request.sources().isEmpty()) {
            throw new ConfigException("ingest requires at least one source");
        }
        int documents = 0;
        int chunks = 0;
        List<String> warnings = new ArrayList<>();

        for (DocumentSource source : request.sources()) {
            Map<String, String> md = source.metadata() == null ? Map.of() : source.metadata();
            String sourceType = md.get("sourceType");
            String sourceId = md.get("sourceId");
            if (sourceId == null || sourceType == null) {
                throw new ConfigException("DocumentSource requires 'sourceId' and 'sourceType' metadata");
            }
            if (!CORPUS_TYPES.contains(sourceType)) {
                throw new ConfigException("source type '" + sourceType
                        + "' is not part of the corpus — dynamic data is served by tools, not RAG");
            }
            DocumentLoader loader = loadersByType.get(sourceType);
            if (loader == null) {
                throw new ConfigException("no loader registered for sourceType '" + sourceType + "'");
            }
            try {
                String title = md.getOrDefault("title", sourceId);
                Document doc = Document.from(loader.loadText(source), new Metadata(Map.of(
                        "sourceId", sourceId, "sourceType", sourceType, "title", title)));
                List<TextSegment> segments = DocumentSplitters.recursive(maxChunkChars, overlapChars).split(doc);

                store.removeBySourceId(sourceId); // idempotent replace
                List<EmbeddedChunk> embedded = embed(segments, sourceId, sourceType, title);
                store.add(embedded);

                documents++;
                chunks += embedded.size();
            } catch (ConfigException ce) {
                throw ce;
            } catch (RuntimeException e) {
                warnings.add("source '" + sourceId + "' failed: " + e.getMessage());
            }
        }
        return new IngestReport(documents, chunks, warnings);
    }

    private List<EmbeddedChunk> embed(List<TextSegment> segments, String sourceId, String sourceType, String title) {
        if (segments.isEmpty()) {
            return List.of();
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<EmbeddedChunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Map<String, String> chunkMeta = new HashMap<>();
            chunkMeta.put("sourceId", sourceId);
            chunkMeta.put("sourceType", sourceType);
            chunkMeta.put("title", title);
            chunkMeta.put("locator", "chunk-" + i);
            chunks.add(new EmbeddedChunk(
                    sourceId + "#" + i, segments.get(i).text(), embeddings.get(i).vector(), chunkMeta));
        }
        return chunks;
    }
}
