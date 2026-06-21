package com.eoiagent.app.reference;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** T-116 assembly: PlatformBuilder.pack(reference).start() yields a usable AgentService, offline (AC1/AC2). */
class ReferencePlatformBootstrapTest {

    @Test
    void referencePackAssemblesIntoAUsableServiceOffline() { // AC1 + AC2
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("The Acme lakehouse ingests data nightly.")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(gateway)
                .start()) {

            assertThat(platform.pack().appId()).isEqualTo(new AppId("acme-lakehouse"));

            AgentSession session = platform.agentService().open(new SessionRequest(
                    new UserId("viewer-1"), Role.USER, DeploymentProfile.OFFLINE, null, Map.of()));
            AgentAnswer answer = session.ask(new UserMessage("How does ingestion work?", null, Instant.now()));

            assertThat(answer.kind()).isNotNull();
            assertThat(answer.text()).contains("nightly");
            session.close();
        }
    }

    @Test
    void appIdAcmeLakehouseIsStampedIntoEveryAuditEvent() {
        RecordingAuditSink sink = new RecordingAuditSink();
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText("ok").build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(gateway)
                .auditSink(sink)
                .start()) {

            AgentSession session = platform.agentService().open(new SessionRequest(
                    new UserId("viewer-1"), Role.USER, DeploymentProfile.OFFLINE, null, Map.of()));
            session.ask(new UserMessage("anything", null, Instant.now()));
            session.close();
        }

        assertThat(sink.events).isNotEmpty();
        assertThat(sink.events).allSatisfy(e -> assertThat(e.app()).isEqualTo(new AppId("acme-lakehouse")));
    }

    @Test
    void closeIsCleanAndIdempotent() {
        AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(StubLlmGateway.builder().defaultReplyText("ok").build())
                .start();
        platform.close();
        platform.close(); // idempotent, no throw
    }

    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }
}
