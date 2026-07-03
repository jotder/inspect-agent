package com.eoiagent.observability;

import java.util.Map;
import java.util.Objects;

/**
 * Default {@link TraceCollector}: spans are no-ops. Keeps tracing optional (spec AC6) — safe to call
 * from every model/tool call site even when no tracing backend is configured, and never egresses.
 */
public final class NoopTraceCollector implements TraceCollector {

    @Override
    public Span start(String name, Map<String, Object> attrs) {
        Objects.requireNonNull(name, "name");
        return new Span(name, name, 0L);
    }

    @Override
    public void end(Span span, SpanStatus status) {
        // no-op
    }
}
