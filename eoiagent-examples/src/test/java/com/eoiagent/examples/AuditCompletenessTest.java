package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-403 audit completeness: a live QA turn through the assembled platform must leave a full,
 * attributable audit trail — the retrieval the model saw, the model call, and the terminal
 * decision, every event stamped with app/run/session/user. If any of these stop being emitted,
 * compliance silently loses its view of what the agent did; this test makes that loud.
 */
class AuditCompletenessTest {

    /** Append-only recording sink (the AuditSink contract has no read side by design). */
    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    @Test
    void qaTurnEmitsFullyAttributedRetrievalModelCallAndDecision() {
        RecordingAuditSink audit = new RecordingAuditSink();
        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyText("Ingestion runs nightly at 02:00 UTC.")
                .defaultReplyText("See the Acme docs.")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(scripted)
                .auditSink(audit)
                .start()) {
            AgentSession session = platform.agentService().open(DemoSupport.session(Role.USER));
            AgentAnswer answer = session.ask(
                    new UserMessage("How often does ingestion run?", null, Instant.now()));
            session.close();
            assertThat(answer.text()).contains("02:00");
        }

        List<AuditKind> kinds = audit.events.stream().map(AuditEvent::kind).toList();
        assertThat(kinds)
                .as("a QA turn must audit what the model saw, the call, and the outcome")
                .contains(AuditKind.RETRIEVAL, AuditKind.MODEL_CALL, AuditKind.DECISION);
        assertThat(kinds.indexOf(AuditKind.RETRIEVAL))
                .as("retrieval is audited before the model call it grounds")
                .isLessThan(kinds.indexOf(AuditKind.MODEL_CALL));
        assertThat(kinds).doesNotContain(AuditKind.ERROR);

        // Attribution: every event traces to who/where/when (audit ≠ logging, ADR-0009).
        for (AuditEvent event : audit.events) {
            assertThat(event.at()).as("timestamp on %s", event.kind()).isNotNull();
            assertThat(event.app()).as("app on %s", event.kind()).isNotNull();
            assertThat(event.run()).as("run on %s", event.kind()).isNotNull();
            assertThat(event.session()).as("session on %s", event.kind()).isNotNull();
            assertThat(event.summary()).as("summary on %s", event.kind()).isNotBlank();
        }
    }
}
