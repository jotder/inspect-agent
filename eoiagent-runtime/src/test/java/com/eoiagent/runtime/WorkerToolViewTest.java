package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WorkerToolView narrows a registry to a worker's capability subset (T-205 AC7 — narrowed tool subset). */
class WorkerToolViewTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static DefaultToolRegistry baseRegistry() {
        DefaultToolRegistry reg = new DefaultToolRegistry(new AllowAllPolicyEngine(), new RecordingAuditSink());
        reg.register(new FixedTool("describeSchema", "schema", Capability.READ_SCHEMA, Role.USER, false));
        reg.register(new FixedTool("readDocs", "docs", Capability.READ_DOCS, Role.USER, false));
        reg.register(new FixedTool("genSql", "SELECT 1", Capability.GENERATE_SQL, Role.USER, false));
        return reg;
    }

    @Test
    void visibleToReturnsOnlyToolsInTheSubset() {
        WorkerToolView analysisView = new WorkerToolView(baseRegistry(),
                Set.of(Capability.READ_METADATA, Capability.READ_SCHEMA, Capability.READ_DOCS));

        assertThat(analysisView.visibleTo(ctx())).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("describeSchema", "readDocs"); // genSql excluded
    }

    @Test
    void dispatchOfAnInSubsetToolDelegates() {
        WorkerToolView analysisView = new WorkerToolView(baseRegistry(),
                Set.of(Capability.READ_SCHEMA, Capability.READ_DOCS));

        assertThat(analysisView.dispatch(new ToolCall("describeSchema", Map.of(), new RunId("r")), ctx()).ok())
                .isTrue();
    }

    @Test
    void dispatchOfAnOutOfSubsetToolFailsClosed() {
        WorkerToolView analysisView = new WorkerToolView(baseRegistry(),
                Set.of(Capability.READ_SCHEMA, Capability.READ_DOCS)); // GENERATE_SQL not granted

        assertThatThrownBy(() -> analysisView.dispatch(new ToolCall("genSql", Map.of(), new RunId("r")), ctx()))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("outside this worker's capability subset");
    }

    @Test
    void registerIsUnsupported() {
        WorkerToolView view = new WorkerToolView(baseRegistry(), Set.of(Capability.READ_DOCS));
        assertThatThrownBy(() -> view.register(new FixedTool("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
