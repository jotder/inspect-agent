package com.eoiagent.platform;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.app.PolicyProfile;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.safety.PolicyEngine;
import com.eoiagent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-354 platform wiring v2: config-first model selection (ADR-0013 §1), the mutating approval
 * stack under MUTATING_ACTIONS (C4: no MUTATION without a preceding APPROVED), and the pack
 * policy profile acting as a restriction overlay under the default grant-table ceiling.
 */
class PlatformWiringV2Test {

    // --- config-first model selection ---------------------------------------------------------

    @Test
    void configOverridesThePackModelSelectionWithoutRecompiling() {
        // The stub pack declares provider "stub", which the factory rejects — the deployment-level
        // config override redirects to a real local provider, so start() succeeds with NO code
        // change and NO injected gateway (nothing talks to the endpoint at assembly time).
        StubApplicationPack pack = new StubApplicationPack().withConfig(StubApplicationPack.packConfig(
                DeploymentProfile.OFFLINE, Map.of(),
                Map.of("eoiagent.model.chat.provider", "ollama",
                        "eoiagent.model.chat.modelId", "swapped-in-model",
                        "eoiagent.model.chat.baseUrl", "http://localhost:9")));

        try (AgentPlatform platform = new PlatformBuilder().pack(pack).start()) {
            assertThat(platform.agentService()).isNotNull();
        }
    }

    // --- policy ceiling -------------------------------------------------------------------------

    @Test
    void packProfileCanNarrowButNeverWidenTheDefaultCeiling() {
        PolicyEngine grantsEverything = new PolicyEngine() {
            @Override
            public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
                return true;
            }

            @Override
            public void check(com.eoiagent.core.AgentContext ctx, ToolSpec tool) {
            }
        };
        CeilingPolicyEngine policy = new CeilingPolicyEngine(grantsEverything);

        // Widen attempt: USER never has RUN_PIPELINE in the default table — pack grant is ignored.
        assertThat(policy.allows(Role.USER, Capability.RUN_PIPELINE, DeploymentProfile.OFFLINE)).isFalse();
        // Within the ceiling the pack's grant passes through.
        assertThat(policy.allows(Role.USER, Capability.READ_DOCS, DeploymentProfile.OFFLINE)).isTrue();

        PolicyEngine grantsNothing = new PolicyEngine() {
            @Override
            public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
                return false;
            }

            @Override
            public void check(com.eoiagent.core.AgentContext ctx, ToolSpec tool) {
                throw new com.eoiagent.core.PolicyViolation("pack denies everything");
            }
        };
        // Narrowing works: even ADMIN loses a capability the pack withholds.
        assertThat(new CeilingPolicyEngine(grantsNothing)
                .allows(Role.ADMIN, Capability.READ_DOCS, DeploymentProfile.OFFLINE)).isFalse();
    }

    // --- mutating stack (C4 invariant through the assembled platform) --------------------------

    /** Grants ADMIN the mutating capability so only the approval gate stands between ask and act. */
    private static final PolicyProfile ADMIN_MUTATING_POLICY = new PolicyProfile() {
        @Override
        public Role mapRole(String hostRole) {
            return Role.ADMIN;
        }

        @Override
        public Set<Capability> grants(Role role) {
            return role == Role.ADMIN
                    ? Set.of(Capability.TRIGGER_JOB, Capability.READ_DOCS)
                    : Set.of(Capability.READ_DOCS);
        }
    };

    private static final class ReloadTool implements Tool {
        volatile int invocations;

        @Override
        public ToolSpec spec() {
            return new ToolSpec("triggerReload", "reloads the mart", "{}",
                    true, Role.ADMIN, Capability.TRIGGER_JOB);
        }

        @Override
        public ToolResult invoke(ToolCall call) {
            invocations++;
            return new ToolResult(true, "reload started", null, Map.of());
        }
    }

    private static AgentAnswer askThroughPlatform(ReloadTool tool, RecordingAuditSink sink,
                                                  com.eoiagent.safety.ApprovalHandler handler) {
        StubApplicationPack pack = new StubApplicationPack()
                .withTools(List.of(tool))
                .withPolicyProfile(ADMIN_MUTATING_POLICY);
        StubLlmGateway gateway = StubLlmGateway.builder()
                .replyToolCalls(new ToolCall("triggerReload", Map.of(), null))
                .replyText("The reload has been started.")
                .defaultReplyText("done")
                .build();

        PlatformBuilder builder = new PlatformBuilder().pack(pack).llmGateway(gateway).auditSink(sink);
        if (handler != null) {
            builder.approvalHandler(handler);
        }
        try (AgentPlatform platform = builder.start()) {
            AgentSession session = platform.agentService().open(new SessionRequest(
                    new UserId("admin-1"), Role.ADMIN, DeploymentProfile.OFFLINE, null, Map.of()));
            AgentAnswer answer = session.ask(new UserMessage("reload the mart", null, Instant.now()));
            session.close();
            return answer;
        }
    }

    @Test
    void approvedMutationRunsAndAuditsApprovalBeforeMutation() { // C4 happy path
        ReloadTool tool = new ReloadTool();
        RecordingAuditSink sink = new RecordingAuditSink();

        AgentAnswer answer = askThroughPlatform(tool, sink, req -> ApprovalDecision.APPROVED);

        assertThat(answer.kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(tool.invocations).isEqualTo(1);
        List<AuditKind> kinds = sink.events.stream().map(AuditEvent::kind).toList();
        assertThat(kinds).contains(AuditKind.APPROVAL, AuditKind.MUTATION);
        assertThat(kinds.indexOf(AuditKind.APPROVAL))
                .as("APPROVAL must precede MUTATION (invariant C4)")
                .isLessThan(kinds.indexOf(AuditKind.MUTATION));
    }

    @Test
    void withoutAHandlerTheGateIsHeadlessAndFailsClosed() { // C4 fail-closed
        ReloadTool tool = new ReloadTool();
        RecordingAuditSink sink = new RecordingAuditSink();

        askThroughPlatform(tool, sink, null);

        assertThat(tool.invocations).as("denied mutation must never execute").isZero();
        List<AuditKind> kinds = sink.events.stream().map(AuditEvent::kind).toList();
        assertThat(kinds).contains(AuditKind.APPROVAL);      // the denial itself is audited
        assertThat(kinds).doesNotContain(AuditKind.MUTATION); // but nothing mutated
    }

    private static final class RecordingAuditSink implements AuditSink {
        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }
}
