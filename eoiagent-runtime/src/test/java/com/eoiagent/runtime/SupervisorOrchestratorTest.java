package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SupervisorOrchestrator drives Flow D: an LLM supervisor delegates to isolated workers and
 * aggregates their results, with isolated scratchpad scopes (AC7) and a hard worker cap (AC8).
 */
class SupervisorOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static Goal goal() {
        return new Goal("is the warehouse healthy?", GoalKind.ANALYSIS);
    }

    @Test
    void delegatesToAWorkerAndAggregatesItsResult() { // Flow D delegation + aggregation
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .toolCall("analysis")                       // supervisor → delegate to analysis
                .finalText("schema is healthy")             // analysis worker concludes
                .finalText("Overall: warehouse healthy");   // supervisor concludes
        DefaultToolRegistry reg = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        SupervisorOrchestrator orch = new SupervisorOrchestrator(
                gateway, reg, new InMemoryScratchpad(), sink, new FakeRuntimeConfig(12, 8192, 3));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).contains("warehouse healthy");
        assertThat(run.steps()).isEqualTo(1); // one delegation
        assertThat(sink.summaries()).contains("delegate to analysis", "aggregate (1 worker)");
    }

    @Test
    void eachWorkerWritesToItsOwnScratchpadScope() { // AC7 (isolation, end-to-end)
        RecordingAuditSink sink = new RecordingAuditSink();
        String bigPayload = "ROW,".repeat(5000); // ~20 KB, over the 8 KB offload threshold
        ScriptedGateway gateway = new ScriptedGateway()
                .toolCall("analysis")           // delegate to analysis
                .toolCall("bigSchema")          // analysis worker calls its tool (offloaded)
                .finalText("done")              // analysis worker concludes
                .finalText("aggregated");       // supervisor concludes
        DefaultToolRegistry reg = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        reg.register(new FixedTool("bigSchema", bigPayload, Capability.READ_SCHEMA, Role.USER, false));
        InMemoryScratchpad scratchpad = new InMemoryScratchpad();
        SupervisorOrchestrator orch = new SupervisorOrchestrator(
                gateway, reg, scratchpad, sink, new FakeRuntimeConfig(12, 8192, 3));

        orch.run(goal(), ctx());

        // The worker's offloaded payload landed under its own worker-namespaced scratchpad scope.
        List<String> keys = scratchpad.list("");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).contains("/worker/analysis/").contains("/tool/");
        assertThat(scratchpad.read(keys.get(0))).isEqualTo(bigPayload);
    }

    @Test
    void delegationNeverExceedsMaxWorkers() { // AC8
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .toolCall("analysis").finalText("a")   // delegation #1 + worker conclude
                .toolCall("sql").finalText("b")        // delegation #2 + worker conclude
                .toolCall("pipeline");                 // a 3rd delegation the cap must prevent
        DefaultToolRegistry reg = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        SupervisorOrchestrator orch = new SupervisorOrchestrator(
                gateway, reg, new InMemoryScratchpad(), sink, new FakeRuntimeConfig(12, 8192, 2)); // cap = 2

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.steps()).isEqualTo(2);
        assertThat(sink.summaries()).contains("delegate to analysis", "delegate to sql");
        assertThat(sink.summaries()).doesNotContain("delegate to pipeline");
        // Stopped before consuming the 3rd supervisor decision: 2×(supervisor + worker) chat calls.
        assertThat(gateway.chatCalls).isEqualTo(4);
    }
}
