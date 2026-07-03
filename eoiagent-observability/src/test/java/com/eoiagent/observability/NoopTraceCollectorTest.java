package com.eoiagent.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/** NoopTraceCollector.start/end never throw and are true no-ops (spec AC6). */
class NoopTraceCollectorTest {

    @Test
    void startAndEndNeverThrow() {
        NoopTraceCollector collector = new NoopTraceCollector();

        assertThatCode(() -> {
            Span span = collector.start("model-call", Map.of("run", "r-1"));
            collector.end(span, SpanStatus.OK);
            collector.end(span, SpanStatus.ERROR); // ending twice is still safe
        }).doesNotThrowAnyException();
    }

    @Test
    void endOnAForeignSpanIsSafe() {
        NoopTraceCollector collector = new NoopTraceCollector();

        assertThatCode(() -> collector.end(new Span("unknown", "unknown", 0L), SpanStatus.ERROR))
                .doesNotThrowAnyException();
    }
}
