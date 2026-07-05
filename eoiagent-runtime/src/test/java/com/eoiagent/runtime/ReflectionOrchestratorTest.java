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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-500: the evaluator-critic reflection loop (draft → critique → revise). Each phase is one model
 * call; the loop stops on the critic's APPROVED verdict or when the revision budget is spent, is
 * fully audited, and never revises unbounded. All tests run offline against a scripted gateway.
 */
class ReflectionOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static Goal goal() {
        return new Goal("write SQL for daily revenue by region", GoalKind.SQL_GEN);
    }

    @Test
    void approvedFirstDraftReturnsTheDraftWithNoRevision() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("SELECT region, sum(amount) FROM sales GROUP BY region")  // draft
                .finalText("APPROVED");                                              // critic accepts
        ReflectionOrchestrator orch = new ReflectionOrchestrator(gateway, sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).isEqualTo("SELECT region, sum(amount) FROM sales GROUP BY region");
        assertThat(run.steps()).isEqualTo(2);                    // draft + one critique, no revise
        assertThat(gateway.chatCalls).isEqualTo(2);
        assertThat(sink.summaries()).contains("reflection: drafted", "reflection round 1: approved");
        assertThat(sink.summaries()).noneMatch(s -> s.contains("revising"));
    }

    @Test
    void reviseThenApproveReturnsTheRevisedDraft() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("SELECT * FROM sales")                        // draft (v1)
                .finalText("Missing GROUP BY and a date filter")        // critic rejects
                .finalText("SELECT region, sum(amount) FROM sales WHERE d = today GROUP BY region")  // revise (v2)
                .finalText("APPROVED");                                 // critic accepts v2
        ReflectionOrchestrator orch = new ReflectionOrchestrator(gateway, sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).contains("GROUP BY region").contains("WHERE");
        assertThat(run.steps()).isEqualTo(4);                   // draft + critique + revise + critique
        assertThat(sink.summaries()).containsSubsequence(
                "reflection: drafted", "reflection round 1: revising", "reflection round 2: approved");
    }

    @Test
    void budgetExhaustionConcludesGracefullyWithTheLatestDraft() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("v1")
                .finalText("needs work")   // critique 1 → revise (budget = 1)
                .finalText("v2")
                .finalText("still off");   // critique 2 → budget spent, stop
        ReflectionOrchestrator orch = new ReflectionOrchestrator(
                gateway, sink, new FakeRuntimeConfig(12, 8192).withReflectionMaxRevisions(1));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT); // never ERROR, never throws
        assertThat(run.answer().text()).isEqualTo("v2");            // the last revision is returned
        assertThat(run.steps()).isEqualTo(4);                       // draft + critique + revise + critique
        assertThat(sink.summaries()).contains("reflection: max revisions (1) reached");
        assertThat(sink.summaries()).noneMatch(s -> s.contains("approved"));
    }

    @Test
    void zeroBudgetNeverRevises() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("v1")
                .finalText("could be better");  // rejected, but no revision budget
        ReflectionOrchestrator orch = new ReflectionOrchestrator(
                gateway, sink, new FakeRuntimeConfig(12, 8192).withReflectionMaxRevisions(0));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().text()).isEqualTo("v1");
        assertThat(run.steps()).isEqualTo(2);                   // draft + one critique only
        assertThat(sink.summaries()).contains("reflection: max revisions (0) reached");
    }

    @Test
    void everyModelCallAndRoundIsAudited() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("v1").finalText("fix it").finalText("v2").finalText("APPROVED");
        ReflectionOrchestrator orch = new ReflectionOrchestrator(gateway, sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(goal(), ctx());

        long modelCalls = sink.kinds().stream().filter(k -> k == AuditKind.MODEL_CALL).count();
        assertThat(modelCalls).isEqualTo(gateway.chatCalls).isEqualTo(run.steps());
    }

    @Test
    void notApprovedVerdictIsNotMistakenForApproval() {
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("v1")
                .finalText("NOT APPROVED — add a filter")  // must NOT be read as approval
                .finalText("v2")
                .finalText("APPROVED");
        ReflectionOrchestrator orch = new ReflectionOrchestrator(gateway, sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().text()).isEqualTo("v2");   // it revised, then got real approval
        assertThat(sink.summaries()).contains("reflection round 1: revising", "reflection round 2: approved");
    }

    @Test
    void modelFaultYieldsErrorAnswerAndAudit() { // spec: run never throws past the boundary
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new RuntimeException("model down"));
        ReflectionOrchestrator orch = new ReflectionOrchestrator(gateway, sink, new FakeRuntimeConfig(12, 8192));

        AgentRun run = orch.run(goal(), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.ERROR);
        assertThat(run.steps()).isZero();
        assertThat(sink.kinds()).contains(AuditKind.ERROR);
    }
}
