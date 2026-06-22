package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.persistence.Checkpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The LangGraph4j-backed investigation orchestrator (Flow E, T-301): a cyclical
 * {@code gather → hypothesize → test → (loop | escalate | conclude)} graph that reaches the model only
 * through the {@code LlmGateway} port, terminates within a bounded round budget, audits every node,
 * and checkpoints after each node.
 */
class LangGraphOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.ANALYST, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static Goal investigation() {
        return new Goal("why is the orders pipeline failing?", GoalKind.INVESTIGATION);
    }

    private static List<String> nodeIds(List<Checkpoint> history) {
        return history.stream().map(Checkpoint::nodeId).toList();
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
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store);

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
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(2, 8192), store);

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
    void escalateVerdict_routesThroughEscalateNodeBeforeConcluding() {
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("repeated data-loss alerts")       // gatherSignals
                .finalText("possible irreversible deletion")  // hypothesize
                .finalText("ESCALATE");                        // testHypothesis → escalate
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store);

        AgentRun run = orch.run(investigation(), ctx());

        assertThat(nodeIds(store.history(run.id())))
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "escalate", "conclude");
        assertThat(run.answer().text()).contains("escalated for human review");
        assertThat(audit.summaries()).anyMatch(s -> s.contains("escalate: human review required"));
    }

    @Test
    void injectedModelFault_yieldsErrorAnswerAndAuditWithoutThrowing() {
        RecordingAuditSink audit = new RecordingAuditSink();
        RecordingCheckpointStore store = new RecordingCheckpointStore();
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new RuntimeException("model down"));
        LangGraphOrchestrator orch =
                new LangGraphOrchestrator(gateway, audit, new FakeRuntimeConfig(12, 8192), store);

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
                new LangGraphOrchestrator(gateway, audit, new NoCheckpointConfig(), store);

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
