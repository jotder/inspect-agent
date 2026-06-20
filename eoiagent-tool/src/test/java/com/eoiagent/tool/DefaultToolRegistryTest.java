package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.ConfigException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Registration, role/profile visibility, and policy + audit enforced dispatch (T-110 AC1–AC3). */
class DefaultToolRegistryTest {

    private static final AgentContext USER_CTX = ctx(Role.USER);
    private static final AgentContext ADMIN_CTX = ctx(Role.ADMIN);

    private static AgentContext ctx(Role role) {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                role, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ToolSpec readOnly(String name, Role required, Capability cap) {
        return new ToolSpec(name, "desc", "{}", false, required, cap);
    }

    private static ToolSpec mutating(String name, Role required, Capability cap) {
        return new ToolSpec(name, "desc", "{}", true, required, cap);
    }

    private static ToolCall call(String name) {
        return new ToolCall(name, Map.of(), new RunId("r1"));
    }

    @Test
    void registerRejectsDuplicateName() { // AC1
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), new RecordingAuditSink());
        reg.register(StubTool.ok(readOnly("t", Role.USER, Capability.READ_DOCS), "x"));

        assertThatThrownBy(() -> reg.register(StubTool.ok(readOnly("t", Role.USER, Capability.READ_METADATA), "y")))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void visibleToHidesToolsAboveTheCallersRole() { // AC1 (invariant 4)
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), new RecordingAuditSink());
        reg.register(StubTool.ok(readOnly("user-tool", Role.USER, Capability.READ_DOCS), "x"));
        reg.register(StubTool.ok(readOnly("admin-tool", Role.ADMIN, Capability.READ_METADATA), "x"));

        assertThat(reg.visibleTo(USER_CTX)).extracting(ToolSpec::name).containsExactly("user-tool");
        assertThat(reg.visibleTo(ADMIN_CTX)).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("user-tool", "admin-tool");
    }

    @Test
    void visibleToHidesCapabilitiesDeniedByPolicy() { // AC1
        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(Capability.RUN_SQL_READONLY), new RecordingAuditSink());
        reg.register(StubTool.ok(readOnly("docs", Role.USER, Capability.READ_DOCS), "x"));
        reg.register(StubTool.ok(readOnly("sql", Role.USER, Capability.RUN_SQL_READONLY), "x"));

        assertThat(reg.visibleTo(USER_CTX)).extracting(ToolSpec::name).containsExactly("docs");
    }

    @Test
    void visibleToHidesMutatingToolsInPhase1() { // AC3
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), new RecordingAuditSink());
        reg.register(new StubTool(mutating("mutate", Role.ADMIN, Capability.RUN_PIPELINE),
                c -> new ToolResult(true, "done", null, Map.of())));

        assertThat(reg.visibleTo(ADMIN_CTX)).isEmpty();
    }

    @Test
    void dispatchReadOnlyInvokesAndEmitsExactlyOneToolCall() { // AC2
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), sink);
        reg.register(StubTool.ok(readOnly("docs", Role.USER, Capability.READ_DOCS), "the docs"));

        ToolResult result = reg.dispatch(call("docs"), USER_CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo("the docs");
        assertThat(sink.kinds()).containsExactly(AuditKind.TOOL_CALL); // no approval interaction
    }

    @Test
    void dispatchUnknownToolReturnsNotOk() {
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), new RecordingAuditSink());

        ToolResult result = reg.dispatch(call("nope"), USER_CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("unknown tool");
    }

    @Test
    void dispatchMutatingToolFailsClosedWithNoAudit() { // AC3 (invariant 2)
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), sink);
        reg.register(new StubTool(mutating("mutate", Role.ADMIN, Capability.RUN_PIPELINE),
                c -> new ToolResult(true, "done", null, Map.of())));

        assertThatThrownBy(() -> reg.dispatch(call("mutate"), ADMIN_CTX)).isInstanceOf(PolicyViolation.class);
        assertThat(sink.events).isEmpty(); // tool never invoked, no MUTATION event
    }

    @Test
    void dispatchDeniedByRoleThrowsPolicyViolation() { // dispatch re-checks policy
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), new RecordingAuditSink());
        reg.register(StubTool.ok(readOnly("admin-tool", Role.ADMIN, Capability.READ_METADATA), "x"));

        assertThatThrownBy(() -> reg.dispatch(call("admin-tool"), USER_CTX)).isInstanceOf(PolicyViolation.class);
    }

    @Test
    void dispatchInvalidArgumentsReturnsNotOkNotException() { // AC2 (spec AC7)
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), sink);
        String schema = "{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\"}}}";
        reg.register(StubTool.ok(new ToolSpec("weather", "d", schema, false, Role.USER, Capability.READ_DOCS), "sunny"));

        ToolResult result = reg.dispatch(new ToolCall("weather", Map.of(), new RunId("r1")), USER_CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("invalid arguments").contains("city");
        assertThat(sink.events).isEmpty(); // tool never ran
    }
}
