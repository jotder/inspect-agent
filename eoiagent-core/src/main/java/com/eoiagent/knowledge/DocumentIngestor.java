package com.eoiagent.knowledge;

import com.eoiagent.core.IngestReport;
import com.eoiagent.core.IngestRequest;

/** Port for ingesting documents into the knowledge store. */
public interface DocumentIngestor {

    IngestReport ingest(IngestRequest request);
}
