package com.eoiagent.observability;

/**
 * A trace span identified by id and name with a start timestamp.
 * Fields provisional — refined by the owning module spec.
 */
public record Span(String id, String name, long startedAtNanos) {
}
