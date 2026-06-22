package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.observability.AuditSink;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-211 — the Phase-2 golden eval set (SQL-generation, schema/metadata analysis, mutating-with-
 * approval), run OFFLINE through a scripted {@link AgentService} and a {@link RecordingAuditSink}.
 * Read-only tool calls are emitted as {@code TOOL_CALL}; an APPROVED mutating action emits
 * {@code APPROVAL} then {@code MUTATION} (Flow C); a DENIED action emits {@code APPROVAL} only. The
 * harness reconstructs tool calls from the {@code TOOL_CALL}/{@code MUTATION} stream, so an approved
 * mutation is observable as a tool call while a denied one is correctly absent (invariant 2).
 */
class Phase2EvalRunnerTest {

    @TestFactory
    Stream<DynamicTest> phase2SuiteRunsGreenOffline() {
        EvalSuite suite = loadSuite();
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedPhase2AgentService(scripts(), sink);

        EvalReport report = new DefaultEvalHarness(new CompositeScorer(), sink::events)
                .run(suite, agent, DeploymentProfile.OFFLINE);

        assertThat(report.total()).isEqualTo(suite.cases().size());

        return report.outcomes().stream().map(o ->
                DynamicTest.dynamicTest(o.case_().id(), () ->
                        assertThat(o.score().pass())
                                .as("case '%s' (%s): %s", o.case_().id(),
                                        o.case_().expect().expectedKind(), o.score().detail())
                                .isTrue()));
    }

    @Test
    void approvedMutationIsAuditedApprovalBeforeMutation() { // invariant 2
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedPhase2AgentService(scripts(), sink);
        AgentSession session = agent.open(new SessionRequest(
                new UserId("eval"), Role.SUPPORT, DeploymentProfile.OFFLINE, page(), Map.of()));

        AgentAnswer answer = session.ask(new UserMessage("Run pipeline pl-1.", page(), Instant.now()));
        session.close();

        List<AuditKind> kindsForRun = sink.events().stream()
                .filter(e -> e.run().equals(answer.run()))
                .map(AuditEvent::kind)
                .toList();
        assertThat(kindsForRun).contains(AuditKind.APPROVAL, AuditKind.MUTATION);
        assertThat(kindsForRun.indexOf(AuditKind.APPROVAL))
                .as("APPROVED approval must precede the MUTATION it authorizes")
                .isLessThan(kindsForRun.indexOf(AuditKind.MUTATION));
    }

    @Test
    void deniedMutationEmitsApprovalButNoMutation() { // invariant 2 (denied path)
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedPhase2AgentService(scripts(), sink);
        AgentSession session = agent.open(new SessionRequest(
                new UserId("eval"), Role.ADMIN, DeploymentProfile.OFFLINE, page(), Map.of()));

        AgentAnswer answer = session.ask(new UserMessage("Delete dataset ds-9.", page(), Instant.now()));
        session.close();

        List<AuditKind> kindsForRun = sink.events().stream()
                .filter(e -> e.run().equals(answer.run()))
                .map(AuditEvent::kind)
                .toList();
        assertThat(kindsForRun).contains(AuditKind.APPROVAL);
        assertThat(kindsForRun).doesNotContain(AuditKind.MUTATION); // denied → never executed
    }

    // --- Negative cases: prove the mutation-aware assertions actually bite -------------------------

    @Test
    void approvedMutationFailsAMustBeAbsentAssertion() {
        EvalCase c = caseOf("neg-approved-present", "Run pipeline pl-1.",
                new Expectation(AnswerKind.TEXT, null,
                        List.of(new ToolCallAssertion("run_pipeline", Map.of(), true)), null, List.of()));

        CaseOutcome o = runOne(c);

        assertThat(o.score().pass()).isFalse(); // run_pipeline executed → mustBeAbsent must fail
        assertThat(o.score().detail()).containsIgnoringCase("must be absent");
    }

    @Test
    void deniedMutationFailsAPresenceAssertion() {
        EvalCase c = caseOf("neg-denied-present", "Delete dataset ds-9.",
                new Expectation(AnswerKind.TEXT, null,
                        List.of(new ToolCallAssertion("delete_dataset", Map.of(), false)), null, List.of()));

        CaseOutcome o = runOne(c);

        assertThat(o.score().pass()).isFalse(); // denied → tool never invoked, presence assertion fails
        assertThat(o.score().detail()).containsIgnoringCase("delete_dataset");
    }

    // --- scripts: prompt -> (run id) -> planned answer + audit-visible tool calls -----------------

    private static Map<String, Function<RunId, Plan>> scripts() {
        Map<String, Function<RunId, Plan>> m = new LinkedHashMap<>();

        // SQL generation / read-only SQL (read-only tools → TOOL_CALL)
        m.put("Generate SQL for total orders by region.",
                r -> readOnly(r, "SELECT region, COUNT(*) FROM orders GROUP BY region;",
                        tool(r, "generate_sql", Map.of("dataset", "orders"))));
        m.put("Run a read-only query for the count of active users.",
                r -> readOnly(r, "There are 4,210 active users.",
                        tool(r, "run_sql_readonly", Map.of("dataset", "users"))));

        // Analysis (read-only schema/metadata tools)
        m.put("Analyze the schema of dataset ds-9.",
                r -> readOnly(r, "Dataset ds-9 has 12 columns; primary key is order_id.",
                        tool(r, "describe_schema", Map.of("datasetId", "ds-9")),
                        tool(r, "read_metadata", Map.of("datasetId", "ds-9"))));
        m.put("Profile the row count of dataset ds-9.",
                r -> readOnly(r, "Dataset ds-9 has 1,240,000 rows.",
                        tool(r, "count_rows", Map.of("datasetId", "ds-9"))));

        // Mutating, APPROVED → APPROVAL then MUTATION
        m.put("Run pipeline pl-1.",
                r -> approved(r, "Pipeline pl-1 started.",
                        tool(r, "run_pipeline", Map.of("pipelineId", "pl-1"))));
        m.put("Set the max batch size to 5000.",
                r -> approved(r, "Config max.batch.size updated to 5000.",
                        tool(r, "edit_config", Map.of("key", "max.batch.size", "value", "5000"))));
        m.put("Trigger the nightly report job.",
                r -> approved(r, "Job nightly_report triggered.",
                        tool(r, "trigger_job", Map.of("jobName", "nightly_report"))));

        // Mutating, DENIED → APPROVAL only, no MUTATION
        m.put("Delete dataset ds-9.",
                r -> denied(r, "Action was not approved; dataset ds-9 was not deleted.",
                        tool(r, "delete_dataset", Map.of("datasetId", "ds-9"))));

        return m;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static PageContext page() {
        return new PageContext("home", Map.of(), Map.of());
    }

    private static EvalSuite loadSuite() {
        try (InputStream in = Phase2EvalRunnerTest.class.getResourceAsStream("/eval/phase2-golden/golden.yaml")) {
            return YamlEvalCaseLoader.load(in);
        } catch (Exception e) {
            throw new AssertionError("failed to load phase2 golden suite", e);
        }
    }

    private static CaseOutcome runOne(EvalCase c) {
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedPhase2AgentService(scripts(), sink);
        EvalReport report = new DefaultEvalHarness(new CompositeScorer(), sink::events)
                .run(new EvalSuite("neg", List.of(c)), agent, DeploymentProfile.OFFLINE);
        return report.outcomes().get(0);
    }

    private static EvalCase caseOf(String id, String prompt, Expectation expect) {
        return new EvalCase(id, prompt, page(), Role.SUPPORT, expect, Set.of());
    }

    private static Plan readOnly(RunId r, String text, ToolCall... tools) {
        return new Plan(answer(text, r), List.of(tools), List.of(), List.of(), List.of());
    }

    private static Plan approved(RunId r, String text, ToolCall... mutations) {
        return new Plan(answer(text, r), List.of(), List.of(mutations), List.of(), List.of());
    }

    private static Plan denied(RunId r, String text, ToolCall... mutations) {
        return new Plan(answer(text, r), List.of(), List.of(), List.of(mutations), List.of());
    }

    private static AgentAnswer answer(String text, RunId r) {
        return new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), r);
    }

    private static ToolCall tool(RunId r, String name, Map<String, Object> args) {
        return new ToolCall(name, args, r);
    }

    private static AuditEvent event(AppId app, SessionId session, UserId user, RunId run,
                                    AuditKind kind, String summary, Map<String, Object> details) {
        return new AuditEvent(Instant.now(), app, run, session, user, kind, summary, details);
    }

    /** A scripted turn: the answer plus the read-only / approved-mutating / denied-mutating tool calls. */
    private record Plan(AgentAnswer answer, List<ToolCall> readOnly, List<ToolCall> approved,
                        List<ToolCall> denied, List<String> sources) {
    }

    /**
     * An {@link AgentService} that answers each prompt and emits the audit trail a real agent would:
     * a {@code TOOL_CALL} per read-only tool; {@code APPROVAL}+{@code MUTATION} per approved mutation;
     * {@code APPROVAL} only per denied mutation — the ground truth the harness reconstructs from.
     */
    private record ScriptedPhase2AgentService(Map<String, Function<RunId, Plan>> scripts, RecordingAuditSink sink)
            implements AgentService {

        @Override
        public AgentSession open(SessionRequest req) {
            AppId app = new AppId("eval-app");
            SessionId session = new SessionId(UUID.randomUUID().toString());
            UserId user = req.user();
            return new AgentSession() {
                @Override
                public AgentAnswer ask(UserMessage msg) {
                    RunId run = new RunId(UUID.randomUUID().toString());
                    Function<RunId, Plan> factory = scripts.get(msg.text());
                    if (factory == null) {
                        throw new IllegalStateException("no phase2 script for prompt: " + msg.text());
                    }
                    Plan plan = factory.apply(run);

                    List<AuditEvent> emitted = new ArrayList<>();
                    emitted.add(event(app, session, user, run, AuditKind.MODEL_CALL, "model: stub", Map.of()));
                    for (ToolCall tc : plan.readOnly()) {
                        emitted.add(event(app, session, user, run, AuditKind.TOOL_CALL, "tool: " + tc.toolName(),
                                Map.of("tool", tc.toolName(), "args", tc.arguments())));
                    }
                    for (ToolCall tc : plan.denied()) {
                        emitted.add(event(app, session, user, run, AuditKind.APPROVAL,
                                "approval DENIED: " + tc.toolName(), Map.of("tool", tc.toolName())));
                    }
                    for (ToolCall tc : plan.approved()) {
                        emitted.add(event(app, session, user, run, AuditKind.APPROVAL,
                                "approval APPROVED: " + tc.toolName(), Map.of("tool", tc.toolName())));
                        emitted.add(event(app, session, user, run, AuditKind.MUTATION, "mutation: " + tc.toolName(),
                                Map.of("tool", tc.toolName(), "args", tc.arguments())));
                    }
                    if (!plan.sources().isEmpty()) {
                        emitted.add(event(app, session, user, run, AuditKind.RETRIEVAL,
                                "retrieved " + plan.sources().size() + " source(s)",
                                Map.of("sourceIds", plan.sources())));
                    }
                    emitted.add(event(app, session, user, run, AuditKind.DECISION, "final answer", Map.of()));
                    emitted.forEach(sink::record);
                    return plan.answer();
                }

                @Override
                public void askStream(UserMessage msg, AnswerSink answerSink) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Collects every emitted {@link AuditEvent}; {@link #events()} hands the harness a snapshot. */
    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> events() {
            return List.copyOf(events);
        }
    }
}
