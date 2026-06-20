package com.eoiagent.core;

import com.eoiagent.knowledge.MetadataFilter;

/** A retrieval query for top-k chunks with an optional metadata filter. */
public record RetrievalQuery(String text, int k, MetadataFilter filter) {
}
