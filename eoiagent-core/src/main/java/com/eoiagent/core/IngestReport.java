package com.eoiagent.core;

import java.util.List;

/** A summary of an ingestion run: document and chunk counts plus warnings. */
public record IngestReport(int documents, int chunks, List<String> warnings) {
}
