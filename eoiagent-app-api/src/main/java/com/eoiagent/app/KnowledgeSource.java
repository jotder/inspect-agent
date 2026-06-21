package com.eoiagent.app;

import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.knowledge.IngestOptions;
import java.util.List;

/**
 * One domain corpus the core {@code DocumentIngestor} loads, splits, embeds and stores. A pack may
 * declare several. {@code resolve()} returns the concrete {@code DocumentSource}s and may be called
 * again for re-ingestion.
 */
public interface KnowledgeSource {

    /** Stable identifier for this source within the pack. */
    String id();

    /** The kind of corpus, which steers loader selection. */
    SourceKind kind();

    /** Chunking/embedding options for this source. */
    IngestOptions options();

    /** Where the documents come from. */
    List<DocumentSource> resolve();
}
