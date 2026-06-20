package com.eoiagent.observability;

import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Slf4jAuditSink emits to a dedicated audit logger, separate from application logging (T-113 AC3). */
class Slf4jAuditSinkTest {

    private static AuditEvent event() {
        return new AuditEvent(Instant.EPOCH, new AppId("app"), new RunId("r1"),
                new SessionId("s1"), new UserId("u1"), AuditKind.MODEL_CALL, "turn 1", Map.of());
    }

    @Test
    void usesADedicatedAuditLoggerDistinctFromApplicationLogging() { // AC3 — audit != logging
        assertThat(Slf4jAuditSink.AUDIT_LOGGER).isEqualTo("com.eoiagent.audit");
        assertThat(Slf4jAuditSink.AUDIT_LOGGER).isNotEqualTo(Slf4jAuditSink.class.getName());
    }

    @Test
    void recordDoesNotThrow() {
        assertThatCode(() -> new Slf4jAuditSink().record(event())).doesNotThrowAnyException();
    }
}
