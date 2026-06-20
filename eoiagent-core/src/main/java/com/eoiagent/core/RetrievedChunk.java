package com.eoiagent.core;

/** A retrieved text chunk with its relevance score and citation. */
public record RetrievedChunk(String text, double score, Citation citation) {
}
