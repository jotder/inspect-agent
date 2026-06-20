package com.eoiagent.observability;

import com.eoiagent.core.AuditEvent;

/** Port that records audit events to a durable sink. */
public interface AuditSink {

    void record(AuditEvent event);
}
