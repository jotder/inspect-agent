package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** ReAct loop drives Flow B to a final answer, offloads large results, bounds steps, fails safe (T-111 AC1–AC3, AC12). */
class ReActOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static Goal qa() {
        return new Goal("what failed in the pipeline?", GoalKind.QA);
    }

    private static DefaultToolRegistry registryWith(String toolName, Object toolValue, RecordingAuditSink sink) {
        DefaultToolRegistry reg = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        reg.register(new FixedTool(toolName, toolValue));
        return reg;
    }

    @Test
    void drivesFlowBToFinalAnswerWithExpectedAudits() { // AC1
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway().toolCall("echo").finalText("the pipeline failed at step 3");
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("echo", "step-3 error", sink), new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(qa(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).isEqualTo("the pipeline failed at step 3");
        assertThat(run.steps()).isEqualTo(2);
        assertThat(sink.kinds()).containsExactly(
                AuditKind.MODEL_CALL, AuditKind.TOOL_CALL, AuditKind.MODEL_CALL, AuditKind.DECISION);
    }

    @Test
    void offloadsLargeToolResultsToScratchpad() { // AC3 (spec); AC2 (backlog)
        RecordingAuditSink sink = new RecordingAuditSink();
        String bigPayload = "ROW,".repeat(5000); // ~20 KB, well over the 8 KB threshold
        ScriptedGateway gateway = new ScriptedGateway().toolCall("dump").finalText("done");
        InMemoryScratchpad scratchpad = new InMemoryScratchpad();
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("dump", bigPayload, sink), scratchpad, sink, new FakeRuntimeConfig(12, 8192));

        orch.run(qa(), ctx());

        // The payload was offloaded to the scratchpad, intact...
        List<String> keys = scratchpad.list("");
        assertThat(keys).hasSize(1);
        assertThat(scratchpad.read(keys.get(0))).isEqualTo(bigPayload);

        // ...and the history fed to the 2nd model turn carries the handle synopsis, not the raw payload.
        ChatMessageRecord toolMsg = gateway.lastMessages.stream()
                .filter(m -> m.role() == ChatRole.TOOL).findFirst().orElseThrow();
        assertThat(toolMsg.text()).contains("stored at").doesNotContain(bigPayload);
        assertThat(toolMsg.text().length()).isLessThan(bigPayload.length());
    }

    @Test
    void terminatesAtMaxStepsWithoutThrowing() { // AC2 (spec) — bounded loop
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway().alwaysToolCall("echo"); // never returns a final answer
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("echo", "small", sink), new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(3, 8192));

        AgentRun run = orch.run(qa(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.CLARIFICATION);
        assertThat(run.steps()).isEqualTo(3);
        assertThat(gateway.chatCalls).isEqualTo(3);
    }

    @Test
    void injectedFaultYieldsErrorAnswerAndAudit() { // AC12
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new RuntimeException("boom"));
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("echo", "x", sink), new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(12, 8192));

        AgentRun[] result = new AgentRun[1];
        assertThatCode(() -> result[0] = orch.run(qa(), ctx())).doesNotThrowAnyException();

        assertThat(result[0].answer().kind()).isEqualTo(AnswerKind.ERROR);
        assertThat(result[0].answer().kind()).isNotNull();
        assertThat(sink.kinds()).contains(AuditKind.ERROR);
    }

    @Test
    void emitsSpansForModelAndToolCallsWhenATraceCollectorIsInjected() { // T-401
        RecordingAuditSink sink = new RecordingAuditSink();
        RecordingTraceCollector trace = new RecordingTraceCollector();
        ScriptedGateway gateway = new ScriptedGateway().toolCall("echo").finalText("done");
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("echo", "ok", sink), new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(12, 8192), trace);

        orch.run(qa(), ctx());

        assertThat(trace.started).containsExactly("model_call", "tool_call", "model_call");
        assertThat(trace.ended).extracting(RecordingTraceCollector.Ended::status)
                .containsOnly(com.eoiagent.observability.SpanStatus.OK);
        assertThat(trace.started).hasSize(trace.ended.size()); // every started span is ended
    }

    @Test
    void defaultCtorNeverThrowsWithoutAnExplicitTraceCollector() { // NoopTraceCollector default (AC6)
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway().finalText("done");
        ReActOrchestrator orch = new ReActOrchestrator(
                gateway, registryWith("echo", "ok", sink), new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(12, 8192));

        assertThatCode(() -> orch.run(qa(), ctx())).doesNotThrowAnyException();
    }
}
