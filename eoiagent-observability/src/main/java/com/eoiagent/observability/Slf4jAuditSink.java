package com.eoiagent.observability;

import com.eoiagent.core.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link AuditSink} that emits each {@link AuditEvent} as a structured line to a <em>dedicated</em>
 * audit logger ({@value #AUDIT_LOGGER}) — deliberately separate from application logging
 * (conventions §7): audit is for compliance, application logs are for developers and may be
 * sampled/filtered/disabled. For dev / low-stakes deployments; the compliance store is
 * {@link FileAuditSink} (or a JDBC sink later).
 */
public final class Slf4jAuditSink implements AuditSink {

    /** Dedicated audit logger name, distinct from any application/class logger. */
    public static final String AUDIT_LOGGER = "com.eoiagent.audit";

    private final Logger log;

    public Slf4jAuditSink() {
        this.log = LoggerFactory.getLogger(AUDIT_LOGGER);
    }

    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        log.info("kind={} app={} run={} session={} user={} summary={} details={}",
                event.kind(),
                value(event.app() == null ? null : event.app().value()),
                value(event.run() == null ? null : event.run().value()),
                value(event.session() == null ? null : event.session().value()),
                value(event.user() == null ? null : event.user().value()),
                event.summary(),
                event.details());
    }

    private static String value(String v) {
        return v == null ? "-" : v;
    }
}
