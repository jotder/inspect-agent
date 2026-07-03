package com.eoiagent.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the bridge against {@link OpenTelemetry#noop()} — no SDK/exporter needed, so this test
 * stays offline (no OTLP egress) while still driving the real opentelemetry-api span lifecycle.
 */
class OpenTelemetryTraceCollectorTest {

    @Test
    void startReturnsANamedSpanAndEndNeverThrows() {
        OpenTelemetryTraceCollector collector = new OpenTelemetryTraceCollector(OpenTelemetry.noop(), "eoiagent-test");

        Span span = collector.start("model-call", Map.of("run", "r-1"));

        assertThat(span.name()).isEqualTo("model-call");
        assertThatCode(() -> collector.end(span, SpanStatus.OK)).doesNotThrowAnyException();
    }

    @Test
    void endOnAnUnknownSpanIsANoop() {
        OpenTelemetryTraceCollector collector = new OpenTelemetryTraceCollector(OpenTelemetry.noop(), "eoiagent-test");

        assertThatCode(() -> collector.end(new Span("never-started", "x", 0L), SpanStatus.ERROR))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullConstructorArgs() {
        assertThatThrownBy(() -> new OpenTelemetryTraceCollector(null, "eoiagent-test"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpenTelemetryTraceCollector(OpenTelemetry.noop(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
