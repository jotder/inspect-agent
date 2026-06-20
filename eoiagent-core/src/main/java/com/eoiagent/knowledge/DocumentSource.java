package com.eoiagent.knowledge;

import java.util.Map;

/**
 * A source document to ingest, identified by URI and media type.
 * Fields provisional — refined by the owning module spec.
 */
public record DocumentSource(String uri, String mediaType, Map<String, String> metadata) {
}
