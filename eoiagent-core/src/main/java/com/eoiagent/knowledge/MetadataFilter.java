package com.eoiagent.knowledge;

import java.util.Map;

/**
 * A set of metadata constraints used to scope retrieval.
 * Fields provisional — refined by the owning module spec.
 */
public record MetadataFilter(Map<String, String> constraints) {

    public static MetadataFilter none() {
        return new MetadataFilter(Map.of());
    }
}
