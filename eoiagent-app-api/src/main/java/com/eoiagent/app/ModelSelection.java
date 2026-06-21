package com.eoiagent.app;

/** Selects one model: its provider id, model id, base URL and whether it runs locally (in-process/on-host). */
public record ModelSelection(String provider, String modelId, String baseUrl, boolean local) {
}
