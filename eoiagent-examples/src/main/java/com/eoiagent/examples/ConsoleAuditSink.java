package com.eoiagent.examples;

import com.eoiagent.core.AuditEvent;
import com.eoiagent.observability.AuditSink;

/**
 * An {@link AuditSink} that prints each event to the console, so a demo can show the audit trail the
 * platform records for every run (the same record compliance would see). For demonstration only — the
 * real sinks are {@code FileAuditSink} / {@code Slf4jAuditSink}.
 */
final class ConsoleAuditSink implements AuditSink {

    @Override
    public void record(AuditEvent event) {
        String app = event.app() == null ? "-" : event.app().value();
        String run = event.run() == null ? "-" : event.run().value();
        System.out.printf("  [audit] %-11s app=%s run=%s : %s%n", event.kind(), app, shorten(run), event.summary());
    }

    private static String shorten(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
