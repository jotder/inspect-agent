package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import com.eoiagent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-353: a successful dispatch of the reserved navigation tool ends the run with a typed
 * NAVIGATION answer (the host routes, never the agent); a rejected proposal flows back to the
 * model as an ordinary tool observation so it can self-correct.
 */
class ReActOrchestratorNavigationTest {

    /** Catalog-validating tool double: knows one page, requires the 'metric' param. */
    private static final class FakeNavigationTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec(NavigationIntent.TOOL_NAME, "navigate", "{}",
                    false, Role.USER, com.eoiagent.core.Capability.READ_DOCS);
        }

        @Override
        public ToolResult invoke(ToolCall call) {
            if (!"kpi-dashboard".equals(call.arguments().get("pageId"))) {
                return new ToolResult(false, null, "unknown page", Map.of());
            }
            Object params = call.arguments().get("params");
            if (!(params instanceof Map<?, ?> m) || !m.containsKey("metric")) {
                return new ToolResult(false, null, "page requires parameter 'metric'", Map.of());
            }
            return new ToolResult(true, Map.of(
                    "targetPageId", "kpi-dashboard",
                    "parameters", Map.of("metric", String.valueOf(m.get("metric"))),
                    "rationale", "Revenue lives on the KPI dashboard."), null, Map.of());
        }
    }

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ReActOrchestrator orchestrator(ScriptedGateway gateway, RecordingAuditSink sink) {
        DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        registry.register(new FakeNavigationTool());
        return ReActOrchestrator.builder()
                .gateway(gateway).tools(registry).scratchpad(new InMemoryScratchpad())
                .audit(sink).config(new FakeRuntimeConfig(12, 8192))
                .build();
    }

    @Test
    void validNavigationProposalEndsTheRunWithATypedIntent() {
        ScriptedGateway gateway = new ScriptedGateway().toolCallWith(new ToolCall(
                NavigationIntent.TOOL_NAME,
                Map.of("pageId", "kpi-dashboard", "params", Map.of("metric", "revenue")), null));
        RecordingAuditSink sink = new RecordingAuditSink();

        AgentRun run = orchestrator(gateway, sink).run(new Goal("where do I see revenue?", GoalKind.QA), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.NAVIGATION);
        assertThat(run.answer().navigation()).isNotNull();
        assertThat(run.answer().navigation().targetPageId()).isEqualTo("kpi-dashboard");
        assertThat(run.answer().navigation().parameters()).containsEntry("metric", "revenue");
        assertThat(run.answer().text()).isEqualTo("Revenue lives on the KPI dashboard.");
        assertThat(gateway.chatCalls).isEqualTo(1); // navigation is terminal — no second model turn
        assertThat(sink.kinds()).containsExactly(
                AuditKind.MODEL_CALL, AuditKind.TOOL_CALL, AuditKind.DECISION);
        assertThat(sink.summaries().get(2)).contains("navigation to kpi-dashboard");
    }

    @Test
    void rejectedProposalFlowsBackSoTheModelCanCorrectItself() {
        ScriptedGateway gateway = new ScriptedGateway()
                .toolCallWith(new ToolCall(NavigationIntent.TOOL_NAME, Map.of("pageId", "nope"), null))
                .finalText("I could not find that page.");
        RecordingAuditSink sink = new RecordingAuditSink();

        AgentRun run = orchestrator(gateway, sink).run(new Goal("open the moon page", GoalKind.QA), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT); // loop continued past the failure
        assertThat(gateway.chatCalls).isEqualTo(2);
        assertThat(gateway.lastMessages).anySatisfy(m ->
                assertThat(m.text()).contains("unknown page")); // the error reached the model
    }
}
