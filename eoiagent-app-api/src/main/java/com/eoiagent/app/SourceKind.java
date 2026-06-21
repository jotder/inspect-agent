package com.eoiagent.app;

/** Closed set of knowledge-source kinds that steer loader selection in the {@code DocumentIngestor}. */
public enum SourceKind {
    PRODUCT_DOC,
    CONFIG_FILE,
    SCHEMA_CONFIG,
    CUSTOM
}
