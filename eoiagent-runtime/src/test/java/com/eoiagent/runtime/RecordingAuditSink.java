package com.eoiagent.runtime;

import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.observability.AuditSink;

import java.util.ArrayList;
import java.util.List;

/** Collects emitted {@link AuditEvent}s so tests can assert kind and ordering. */
final class RecordingAuditSink implements AuditSink {

    final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    List<AuditKind> kinds() {
        return events.stream().map(AuditEvent::kind).toList();
    }
}
