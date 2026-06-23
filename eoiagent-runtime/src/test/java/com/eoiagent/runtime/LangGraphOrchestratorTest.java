package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.persistence.Checkpoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The LangGraph4j-backed investigation orchestrator (Flow E): a cyclical
 * {@code gather → hypothesize → test → (loop | escalate | conclude)} graph that reaches the model only
 * through the {@code LlmGateway} port, terminates within a bounded round budget, audits every node, and
 * checkpoints after each node (T-301). The escalate node is a human-in-the-loop breakpoint routed
 * through the {@code ApprovalGate}, and a run can be resumed from its latest checkpoint (T-303).
 */
class LangGraphOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.ANALYST, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static Goal investigation() {
        return new Goal("why is the orders pipeline failing?", GoalKind.INVESTIGATION);
    }

    private static ScriptedApprovalGate approve() {
        return new ScriptedApprovalGate(ApprovalDecision.APPROVED);
    }

    private static List<String> nodeIds(List<Checkpoint> history) {
        return history.stream().map(Checkpoint::nodeId).toList();
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void confirmedHypothesis_concludesAndCheckpointsEachNodeInOrder() {
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("elevated 5xx and DB connection timeouts") // gatherSignals
                .finalText("connection pool exhausted under load")     // hypothesize
                .finalText("CONFIRMED");                               // testHypothesis
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store, approve());

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).contains("connection pool exhausted under load");
        // One checkpoint per executed node, oldest → newest, with strictly increasing seq.
        List<Checkpoint> history = store.history(run.id());
        assertThat(nodeIds(history))
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "conclude");
        assertThat(history).extracting(Checkpoint::seq).containsExactly(0, 1, 2, 3);
        assertThat(run.steps()).isEqualTo(4);
        assertThat(audit.kinds()).containsSubsequence(
                AuditKind.MODEL_CALL, AuditKind.MODEL_CALL, AuditKind.DECISION, AuditKind.DECISION);
    }

    @Test
    void inconclusiveHypothesis_loopsThenConcludesWithinRoundBudget() { // cyclical + bounded
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        // maxSteps=2 → at most two hypothesize/test rounds; both inconclusive, so it must still conclude.
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("sparse signals")    // gatherSignals
                .finalText("hypothesis A")       // hypothesize (round 1)
                .finalText("INCONCLUSIVE")       // testHypothesis (round 1) → loop
                .finalText("hypothesis B")       // hypothesize (round 2)
                .finalText("INCONCLUSIVE");      // testHypothesis (round 2) → budget spent → conclude
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(2, 8192), store, approve());

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).contains("hypothesis B"); // last hypothesis carried forward
        // The cycle re-entered hypothesize/test exactly twice, then concluded (no escalate).
        List<String> nodes = nodeIds(store.history(run.id()));
        assertThat(nodes).containsExactly(
                "gatherSignals", "hypothesize", "testHypothesis", "hypothesize", "testHypothesis", "conclude");
        assertThat(nodes).filteredOn("hypothesize"::equals).hasSize(2);
        assertThat(nodes).doesNotContain("escalate");
    }

    @Test
    void breakpoint_approvedEscalation_routesThroughEscalateThenConcludes() { // T-303 HITL (approved)
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("repeated data-loss alerts")       // gatherSignals
                .finalText("possible irreversible deletion")  // hypothesize
                .finalText("ESCALATE");                        // testHypothesis → escalate
        ScriptedApprovalGate gate = approve();
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store, gate);

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(gate.requestCalls).isEqualTo(1); // the breakpoint asked the gate before escalating
        assertThat(nodeIds(store.history(run.id())))
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "escalate", "conclude");
        assertThat(run.answer().text()).contains("escalated for human review");
        assertThat(audit.kinds()).contains(AuditKind.APPROVAL);
        assertThat(audit.summaries()).anyMatch(s -> s.contains("escalate: human review approved"));
    }

    @Test
    void breakpoint_deniedApproval_concludesWithoutEscalating() { // T-303 HITL (denied → fail closed)
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("repeated data-loss alerts")
                .finalText("possible irreversible deletion")
                .finalText("ESCALATE");
        ScriptedApprovalGate gate = new ScriptedApprovalGate(ApprovalDecision.DENIED);
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store, gate);

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(gate.requestCalls).isEqualTo(1);
        // It still routes through the escalate node, but the denied gate means it does not escalate.
        assertThat(nodeIds(store.history(run.id())))
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "escalate", "conclude");
        assertThat(run.answer().text()).contains("escalation was not approved");
        assertThat(audit.kinds()).contains(AuditKind.APPROVAL);
    }

    @Test
    void resume_continuesFromNextNodeWithoutReRunningCompletedNodes() { // T-303 AC9
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        RunId run = new RunId("inv-1");
        // Simulate a crash after hypothesize: the store already holds gather + hypothesize checkpoints.
        store.save(run, new Checkpoint(run, "gatherSignals",
                json("{\"signals\":[\"elevated 5xx\"]}"), Instant.now(), 0));
        store.save(run, new Checkpoint(run, "hypothesize",
                json("{\"signals\":[\"elevated 5xx\"],\"hypothesis\":\"pool exhausted\"}"), Instant.now(), 1));

        // Only testHypothesis remains to call the model (conclude makes no model call).
        ScriptedGateway gateway = new ScriptedGateway().finalText("CONFIRMED");
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store, approve());

        AgentRun resumed = orch.resume(run, investigation(), ctx());

        assertThat(resumed.id()).isEqualTo(run); // same run, not a new one
        assertThat(resumed.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(resumed.answer().text()).contains("pool exhausted"); // carried from the resumed state
        assertThat(gateway.chatCalls).isEqualTo(1); // gather + hypothesize were NOT re-run
        // The resumed run continues the checkpoint sequence after the saved nodes.
        List<Checkpoint> history = store.history(run);
        assertThat(nodeIds(history))
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "conclude");
        assertThat(history).extracting(Checkpoint::seq).containsExactly(0, 1, 2, 3);
        assertThat(resumed.steps()).isEqualTo(2); // only testHypothesis + conclude executed this time
    }

    @Test
    void resume_withNoCheckpoint_failsWithConfigException() {
        LangGraphOrchestrator orch = new LangGraphOrchestrator(new ScriptedGateway(), new RecordingAuditSink(),
                new FakeRuntimeConfig(12, 8192), new RecordingCheckpointStore(), approve());

        assertThatThrownBy(() -> orch.resume(new RunId("missing"), investigation(), ctx()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("no checkpoint");
    }

    @Test
    void injectedModelFault_yieldsErrorAnswerAndAuditWithoutThrowing() {
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new RuntimeException("model down"));
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store, approve());

        AgentRun[] result = new AgentRun[1];
        assertThatCode(() -> result[0] = orch.run(investigation(), ctx())).doesNotThrowAnyException();

        assertThat(result[0].answer().kind()).isEqualTo(AnswerKind.ERROR);
        assertThat(audit.kinds()).contains(AuditKind.ERROR);
    }

    @Test
    void checkpointEveryNodeDisabled_savesNoCheckpointsButStillConcludes() {
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("signal").finalText("hypothesis").finalText("CONFIRMED");
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new NoCheckpointConfig(), store, approve());

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(store.history(run.id())).isEmpty();
    }

    /** OFFLINE config with checkpoint-every-node turned off; everything else at defaults. */
    private static final class NoCheckpointConfig implements ConfigProvider {
        @Override
        public DeploymentProfile profile() {
            return DeploymentProfile.OFFLINE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(ConfigKey<T> key) {
            if (key.name().equals(RuntimeConfigKeys.CHECKPOINT_EVERY_NODE.name())) {
                return (T) Boolean.FALSE;
            }
            return key.defaultValue();
        }

        @Override
        public boolean featureEnabled(Feature feature) {
            return true;
        }
    }
}
