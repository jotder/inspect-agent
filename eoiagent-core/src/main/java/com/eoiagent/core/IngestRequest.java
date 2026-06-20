package com.eoiagent.core;

import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.knowledge.IngestOptions;
import java.util.List;

/** A request to ingest one or more document sources with options. */
public record IngestRequest(List<DocumentSource> sources, IngestOptions options) {
}
