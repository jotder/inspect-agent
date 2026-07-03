package com.eoiagent.runtime;

import com.eoiagent.observability.Span;
import com.eoiagent.observability.SpanStatus;
import com.eoiagent.observability.TraceCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Collects started/ended spans so tests can assert names, pairing, and status. */
final class RecordingTraceCollector implements TraceCollector {

    record Ended(String name, SpanStatus status) {
    }

    final List<String> started = new ArrayList<>();
    final List<Ended> ended = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public Span start(String name, Map<String, Object> attrs) {
        started.add(name);
        return new Span(name + "-" + seq.incrementAndGet(), name, 0L);
    }

    @Override
    public void end(Span span, SpanStatus status) {
        ended.add(new Ended(span.name(), status));
    }
}
