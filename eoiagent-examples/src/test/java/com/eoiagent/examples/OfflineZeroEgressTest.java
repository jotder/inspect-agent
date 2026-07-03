package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.safety.EgressGuard;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-403: the OFFLINE zero-egress proof. The entire platform lifecycle — bootstrap (ONNX embedding
 * load + corpus ingestion), a real RAG-grounded ask, and shutdown — runs under the in-JVM
 * network-deny harness ({@link EgressGuard}); a single non-loopback connection attempt anywhere in
 * the stack fails the test. This proves 03-deployment-profiles' rule that OFFLINE is network-free
 * on the live path, not just config-gated.
 */
class OfflineZeroEgressTest {

    @Test
    void fullPlatformLifecycleAttemptsNoEgress() {
        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyText("Ingestion runs nightly at 02:00 UTC.")
                .defaultReplyText("See the Acme docs.")
                .build();

        try (EgressGuard guard = EgressGuard.install()) {
            try (AgentPlatform platform = new com.eoiagent.platform.PlatformBuilder()
                    .pack(new ReferenceApplicationPack())
                    .llmGateway(scripted)
                    .start()) {

                AgentSession session = platform.agentService().open(DemoSupport.session(Role.USER));
                AgentAnswer answer = session.ask(
                        new UserMessage("How often does ingestion run?", null, Instant.now()));
                session.close();

                assertThat(answer.kind()).isEqualTo(AnswerKind.TEXT);
                assertThat(answer.text()).contains("02:00");
                assertThat(answer.citations()).isNotEmpty(); // retrieval really ran, in-JVM
            }

            assertThat(guard.attempts())
                    .as("OFFLINE platform lifecycle must attempt zero network egress")
                    .isEmpty();
        }
    }
}
