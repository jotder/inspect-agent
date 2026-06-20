package com.eoiagent.model;

/** Identifying information about a concrete model. */
public record ModelInfo(String provider, String modelId, boolean local) {
}
