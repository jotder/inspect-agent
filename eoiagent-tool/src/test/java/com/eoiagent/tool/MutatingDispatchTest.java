package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mutating dispatch through the ApprovalGate: the C4 invariant (no MUTATION without APPROVED), T-203. */
class MutatingDispatchTest {

    private static final AgentContext ADMIN = new AgentContext(new AppId("app"), new SessionId("s"),
            new UserId("u"), Role.ADMIN, DeploymentProfile.ON_PREM_HOSTED, null, Map.of());

    private static ToolSpec mutating(String name) {
        return new ToolSpec(name, "desc", "{}", true, Role.ADMIN, Capability.RUN_PIPELINE);
    }

    private static ToolCall call(String name) {
        return new ToolCall(name, Map.of(), new RunId("r1"));
    }

    private static DefaultToolRegistry registry(RecordingAuditSink sink, ApprovalDecision decision,
                                                boolean mutatingEnabled, AtomicBoolean invoked) {
        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(),
                new ScriptedApprovalGate(decision),
                new FakeConfigProvider(DeploymentProfile.ON_PREM_HOSTED, mutatingEnabled),
                sink);
        reg.register(new StubTool(mutating("run_pipeline"), c -> {
            invoked.set(true);
            return new ToolResult(true, "ran", null, Map.of());
        }));
        return reg;
    }

    @Test
    void approvedMutationEmitsApprovalThenMutationAndInvokes() { // AC4
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        DefaultToolRegistry reg = registry(sink, ApprovalDecision.APPROVED, true, invoked);

        ToolResult result = reg.dispatch(call("run_pipeline"), ADMIN);

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo("ran");
        assertThat(invoked).isTrue();
        // APPROVAL strictly precedes MUTATION (invariant 2 / C4).
        assertThat(sink.kinds()).containsExactly(AuditKind.APPROVAL, AuditKind.MUTATION);
    }

    @Test
    void deniedApprovalDoesNotMutate() { // AC5
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        DefaultToolRegistry reg = registry(sink, ApprovalDecision.DENIED, true, invoked);

        ToolResult result = reg.dispatch(call("run_pipeline"), ADMIN);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("approval DENIED");
        assertThat(invoked).isFalse();                          // tool never invoked
        assertThat(sink.kinds()).containsExactly(AuditKind.APPROVAL); // no MUTATION event
    }

    @Test
    void timedOutApprovalDoesNotMutate() { // AC5 (timeout)
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        DefaultToolRegistry reg = registry(sink, ApprovalDecision.TIMED_OUT, true, invoked);

        ToolResult result = reg.dispatch(call("run_pipeline"), ADMIN);

        assertThat(result.ok()).isFalse();
        assertThat(invoked).isFalse();
        assertThat(sink.kinds()).doesNotContain(AuditKind.MUTATION);
    }

    @Test
    void mutatingActionsDisabledFailsClosedBeforeApproval() { // AC6
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        ScriptedApprovalGate gate = new ScriptedApprovalGate(ApprovalDecision.APPROVED);
        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(), gate,
                new FakeConfigProvider(DeploymentProfile.OFFLINE, false), sink); // feature OFF
        reg.register(new StubTool(mutating("run_pipeline"), c -> {
            invoked.set(true);
            return new ToolResult(true, "ran", null, Map.of());
        }));

        assertThatThrownBy(() -> reg.dispatch(call("run_pipeline"), ADMIN))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("MUTATING_ACTIONS");
        assertThat(gate.requestCalls).isZero();   // no approval attempted
        assertThat(invoked).isFalse();            // no invoke
        assertThat(sink.events).isEmpty();        // no audit before the fail-closed throw
    }

    @Test
    void mutatingToolVisibleOnlyWhenFeatureEnabled() {
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry enabled = registry(sink, ApprovalDecision.APPROVED, true, new AtomicBoolean());
        DefaultToolRegistry disabled = registry(sink, ApprovalDecision.APPROVED, false, new AtomicBoolean());

        assertThat(enabled.visibleTo(ADMIN)).extracting(ToolSpec::name).containsExactly("run_pipeline");
        assertThat(disabled.visibleTo(ADMIN)).isEmpty();
    }
}
