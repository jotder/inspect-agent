package com.eoiagent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges {@link TraceCollector} spans to an OpenTelemetry {@link Tracer} (T-401, ADR-0010
 * quarantine: this is the only class in the reactor touching {@code io.opentelemetry.*}). Phase 4,
 * optional — a host that never constructs this adapter never loads the dependency. Attributes are
 * stringified (spec: must not carry secrets or full prompts — callers are responsible for that).
 */
public final class OpenTelemetryTraceCollector implements TraceCollector {

    private final Tracer tracer;
    private final ConcurrentHashMap<String, io.opentelemetry.api.trace.Span> openSpans = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public OpenTelemetryTraceCollector(OpenTelemetry openTelemetry, String instrumentationName) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(instrumentationName, "instrumentationName");
        this.tracer = openTelemetry.getTracer(instrumentationName);
    }

    @Override
    public Span start(String name, Map<String, Object> attrs) {
        Objects.requireNonNull(name, "name");
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
        if (attrs != null) {
            attrs.forEach((key, value) -> {
                if (value != null) {
                    builder.setAttribute(key, value.toString());
                }
            });
        }
        io.opentelemetry.api.trace.Span otelSpan = builder.startSpan();
        String id = name + "-" + seq.incrementAndGet();
        openSpans.put(id, otelSpan);
        return new Span(id, name, System.nanoTime());
    }

    @Override
    public void end(Span span, SpanStatus status) {
        Objects.requireNonNull(span, "span");
        io.opentelemetry.api.trace.Span otelSpan = openSpans.remove(span.id());
        if (otelSpan == null) {
            return; // unknown/already-ended span: safe no-op, tracing never gates behavior
        }
        otelSpan.setStatus(status == SpanStatus.ERROR ? StatusCode.ERROR : StatusCode.OK);
        otelSpan.end();
    }
}
