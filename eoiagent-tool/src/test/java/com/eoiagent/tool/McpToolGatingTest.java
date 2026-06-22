package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ApprovalDecision;
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

/** MCP_TOOLS gating of {@link McpTool}s in the registry (tool-registry spec AC9), T-209. */
class McpToolGatingTest {

    private static final AgentContext ADMIN = new AgentContext(new AppId("app"), new SessionId("s"),
            new UserId("u"), Role.ADMIN, DeploymentProfile.ON_PREM_HOSTED, null, Map.of());

    /** A read-only tool carrying the {@link McpTool} marker, without any MCP connection. */
    static final class FakeMcpTool implements McpTool {
        private final ToolSpec spec;
        private final AtomicBoolean invoked;

        FakeMcpTool(String name, AtomicBoolean invoked) {
            this.spec = new ToolSpec(name, "remote tool", "{}", false, Role.USER, Capability.READ_DOCS);
            this.invoked = invoked;
        }

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult invoke(ToolCall call) {
            invoked.set(true);
            return new ToolResult(true, "remote ok", null, Map.of());
        }
    }

    private static DefaultToolRegistry registry(RecordingAuditSink sink, boolean mcpEnabled, AtomicBoolean invoked) {
        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(),
                new ScriptedApprovalGate(ApprovalDecision.APPROVED),
                new FakeConfigProvider(DeploymentProfile.ON_PREM_HOSTED, false, mcpEnabled),
                sink);
        reg.register(new FakeMcpTool("remote_echo", invoked));
        return reg;
    }

    @Test
    void mcpToolHiddenAndFailsClosedWhenDisabled() { // AC9
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        DefaultToolRegistry reg = registry(sink, false, invoked);

        assertThat(reg.visibleTo(ADMIN)).isEmpty(); // hidden from the model

        assertThatThrownBy(() -> reg.dispatch(new ToolCall("remote_echo", Map.of(), new RunId("r1")), ADMIN))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("MCP_TOOLS");
        assertThat(invoked).isFalse();         // never invoked (no network)
        assertThat(sink.events).isEmpty();     // failed closed before any audit
    }

    @Test
    void mcpToolVisibleAndDispatchableWhenEnabled() {
        RecordingAuditSink sink = new RecordingAuditSink();
        AtomicBoolean invoked = new AtomicBoolean(false);
        DefaultToolRegistry reg = registry(sink, true, invoked);

        assertThat(reg.visibleTo(ADMIN)).extracting(ToolSpec::name).containsExactly("remote_echo");

        ToolResult result = reg.dispatch(new ToolCall("remote_echo", Map.of(), new RunId("r1")), ADMIN);

        assertThat(result.ok()).isTrue();
        assertThat(invoked).isTrue();
    }

    @Test
    void readOnlyRegistryAlwaysBlocksMcpTools() {
        // The two-arg (Phase-1) registry has no config, so MCP_TOOLS can never be on.
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), sink);
        reg.register(new FakeMcpTool("remote_echo", new AtomicBoolean()));

        assertThat(reg.visibleTo(ADMIN)).isEmpty();
        assertThatThrownBy(() -> reg.dispatch(new ToolCall("remote_echo", Map.of(), new RunId("r1")), ADMIN))
                .isInstanceOf(PolicyViolation.class);
    }
}
