package com.eoiagent.observability;

import java.util.Map;

/** Port that starts and ends trace spans. */
public interface TraceCollector {

    Span start(String name, Map<String, Object> attrs);

    void end(Span span, SpanStatus status);
}
