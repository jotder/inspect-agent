package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.NavigationIntent;
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
 * T-115 — the MVP golden eval set, run OFFLINE through a scripted {@link AgentService} and a
 * {@link RecordingAuditSink}. Each YAML case becomes a dynamic JUnit test (eval-harness spec
 * §Test plan). Tool-call and citation assertions are reconstructed from the audit
 * {@code TOOL_CALL}/{@code RETRIEVAL} stream by the harness, so the suite verifies the same record
 * compliance reads — never an internal hook (spec AC4; T-115 AC1–AC3).
 */
class GoldenEvalRunnerTest {

    private static final PageContext HOME = new PageContext("home", Map.of(), Map.of());

    @TestFactory
    Stream<DynamicTest> goldenSuiteRunsGreenOffline() { // AC1 (>=20 cases), AC2 (OFFLINE green), AC3 (audit stream)
        EvalSuite suite = loadGolden();
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedGoldenAgentService(goldenScripts(), sink);

        EvalReport report = new DefaultEvalHarness(new CompositeScorer(), sink::events)
                .run(suite, agent, DeploymentProfile.OFFLINE);

        assertThat(report.total())
                .as("golden set must have >=20 cases (T-115 AC1)")
                .isGreaterThanOrEqualTo(20);
        assertThat(report.total()).isEqualTo(suite.cases().size());

        return report.outcomes().stream().map(o ->
                DynamicTest.dynamicTest(o.case_().id(), () ->
                        assertThat(o.score().pass())
                                .as("case '%s' (%s): %s", o.case_().id(),
                                        o.case_().expect().expectedKind(), o.score().detail())
                                .isTrue()));
    }

    // --- Negative cases: prove the audit-derived assertions actually bite (not vacuously pass) ----

    @Test
    void mutatingToolInvocationFailsMustBeAbsentAssertion() { // AC4
        EvalCase c = caseOf("neg-mutating", "do the thing",
                new Expectation(AnswerKind.TEXT, new AnswerAssertion(MatchMode.CONTAINS, "done", 0.0),
                        List.of(new ToolCallAssertion("run_pipeline", Map.of(), true)), null, List.of()));

        CaseOutcome o = runOne(c, r -> textTools(r, "done", tool(r, "run_pipeline", Map.of("pipelineId", "pl-1"))));

        assertThat(o.score().pass()).isFalse();
        assertThat(o.score().detail()).containsIgnoringCase("must be absent");
    }

    @Test
    void missingRequiredCitationFails() {
        EvalCase c = caseOf("neg-citation", "explain x",
                new Expectation(AnswerKind.TEXT, new AnswerAssertion(MatchMode.CONTAINS, "ok", 0.0),
                        List.of(), null, List.of("docs/needed")));

        CaseOutcome o = runOne(c, r -> text(r, "ok")); // emits no RETRIEVAL event

        assertThat(o.score().pass()).isFalse();
        assertThat(o.score().detail()).containsIgnoringCase("missing required source");
    }

    @Test
    void toolPresentWithWrongArgsFails() {
        EvalCase c = caseOf("neg-wrong-args", "status?",
                new Expectation(AnswerKind.TEXT, new AnswerAssertion(MatchMode.CONTAINS, "running", 0.0),
                        List.of(new ToolCallAssertion("get_pipeline_status", Map.of("pipelineId", "pl-999"), false)),
                        null, List.of()));

        CaseOutcome o = runOne(c, r -> textTools(r, "running",
                tool(r, "get_pipeline_status", Map.of("pipelineId", "pl-123"))));

        assertThat(o.score().pass()).isFalse();
        assertThat(o.score().detail()).containsIgnoringCase("get_pipeline_status");
    }

    // --- golden script: prompt -> (run id) -> planned answer + audit-visible tool calls/sources ---

    private static Map<String, Function<RunId, Plan>> goldenScripts() {
        Map<String, Function<RunId, Plan>> m = new LinkedHashMap<>();

        // TEXT — product help (CONTAINS)
        m.put("What is the EOI platform?",
                r -> text(r, "EOI is an embeddable operational intelligence agent platform for your product."));
        m.put("Which data sources can I connect?",
                r -> text(r, "You can connect Iceberg tables, JDBC databases, and object stores."));
        m.put("Does EOI run fully offline?",
                r -> text(r, "Yes — EOI runs fully offline with local models in the OFFLINE profile."));
        m.put("What roles exist in EOI?",
                r -> text(r, "The roles are admin, support, analyst, and user."));
        m.put("What export formats are supported?",
                r -> text(r, "Exports are available as csv, json, and parquet."));
        m.put("How do I authenticate to the API?",
                r -> text(r, "Authenticate by passing a bearer token in the Authorization header."));

        // TEXT — exact / regex
        m.put("What is the current platform version?", r -> text(r, "0.1.0"));
        m.put("ping", r -> text(r, "pong"));
        m.put("What is the typical interactive query latency?",
                r -> text(r, "Typical interactive query latency is around 250 ms."));
        m.put("Is there a rate limit on the API?",
                r -> text(r, "Yes, the API enforces a rate limit of 100 requests per minute."));

        // TEXT — RAG citations (drive the RETRIEVAL audit events)
        m.put("Explain Iceberg tables in our stack.",
                r -> textCited(r, "Iceberg is an open table format we use for large analytic datasets.",
                        List.of("docs/iceberg-overview")));
        m.put("What is our data retention policy?",
                r -> textCited(r, "Data is retained for 90 days by default, then archived.",
                        List.of("docs/retention-policy", "docs/compliance")));

        // TEXT — read-only tool calls
        m.put("What is the status of pipeline pl-123?",
                r -> textTools(r, "Pipeline pl-123 is currently running and healthy.",
                        tool(r, "get_pipeline_status", Map.of("pipelineId", "pl-123"))));
        m.put("How many rows are in dataset ds-9?",
                r -> textTools(r, "Dataset ds-9 has 1,240,000 rows.",
                        tool(r, "count_rows", Map.of("datasetId", "ds-9"))));
        m.put("Summarize the health of pipeline pl-123.",
                r -> textTools(r, "Pipeline pl-123 looks healthy: the last 20 runs all succeeded.",
                        tool(r, "get_pipeline_status", Map.of("pipelineId", "pl-123"))));

        // NAVIGATION
        m.put("Why did the ingestion pipeline pl-123 fail last quarter?",
                r -> navTools(r, "pipeline-run-history",
                        Map.of("pipelineId", "pl-123", "status", "FAILED", "period", "Q3"),
                        List.of(), tool(r, "list_pipeline_runs", Map.of("pipelineId", "pl-123"))));
        m.put("Take me to the revenue dashboard.",
                r -> navTools(r, "dashboard", Map.of("dashboardId", "rev-1"), List.of()));
        m.put("Show me the incident for alert al-7.",
                r -> navTools(r, "incident-detail", Map.of("alertId", "al-7"), List.of()));
        m.put("Open dataset ds-9.",
                r -> navTools(r, "dataset-detail", Map.of("datasetId", "ds-9"), List.of()));
        m.put("Where do I configure data retention?",
                r -> navTools(r, "settings", Map.of("section", "retention"),
                        List.of("docs/retention-policy")));

        // CLARIFICATION
        m.put("fix it", r -> clarify(r, "Which item would you like me to fix?"));
        m.put("tell me everything", r -> clarify(r, "That's quite broad — could you narrow it down?"));

        // ERROR — as an answer kind, and via a thrown fault
        m.put("return an error answer", r -> errorAnswer(r, "The request could not be completed."));
        m.put("trigger a model outage",
                r -> fault(new ModelUnavailableException("no model is available in the OFFLINE profile")));

        return m;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static EvalSuite loadGolden() {
        try (InputStream in = GoldenEvalRunnerTest.class.getResourceAsStream("/eval/phase1-golden/golden.yaml")) {
            return YamlEvalCaseLoader.load(in);
        } catch (Exception e) {
            throw new AssertionError("failed to load golden suite", e);
        }
    }

    private static CaseOutcome runOne(EvalCase c, Function<RunId, Plan> script) {
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new ScriptedGoldenAgentService(Map.of(c.prompt(), script), sink);
        EvalReport report = new DefaultEvalHarness(new CompositeScorer(), sink::events)
                .run(new EvalSuite("neg", List.of(c)), agent, DeploymentProfile.OFFLINE);
        return report.outcomes().get(0);
    }

    private static EvalCase caseOf(String id, String prompt, Expectation expect) {
        return new EvalCase(id, prompt, HOME, Role.ANALYST, expect, Set.of());
    }

    private static Plan text(RunId r, String t) {
        return new Plan(answer(AnswerKind.TEXT, t, null, r), List.of(), List.of(), null);
    }

    private static Plan textCited(RunId r, String t, List<String> sources) {
        return new Plan(answer(AnswerKind.TEXT, t, null, r), List.of(), List.copyOf(sources), null);
    }

    private static Plan textTools(RunId r, String t, ToolCall... tools) {
        return new Plan(answer(AnswerKind.TEXT, t, null, r), List.of(tools), List.of(), null);
    }

    private static Plan navTools(RunId r, String target, Map<String, String> params, List<String> sources,
                                 ToolCall... tools) {
        NavigationIntent nav = new NavigationIntent(target, params, "navigating to " + target);
        return new Plan(answer(AnswerKind.NAVIGATION, "", nav, r), List.of(tools), List.copyOf(sources), null);
    }

    private static Plan clarify(RunId r, String t) {
        return new Plan(answer(AnswerKind.CLARIFICATION, t, null, r), List.of(), List.of(), null);
    }

    private static Plan errorAnswer(RunId r, String t) {
        return new Plan(answer(AnswerKind.ERROR, t, null, r), List.of(), List.of(), null);
    }

    private static Plan fault(RuntimeException e) {
        return new Plan(null, List.of(), List.of(), e);
    }

    private static AgentAnswer answer(AnswerKind kind, String text, NavigationIntent nav, RunId r) {
        return new AgentAnswer(kind, text, null, nav, List.of(), r);
    }

    private static ToolCall tool(RunId r, String name, Map<String, Object> args) {
        return new ToolCall(name, args, r);
    }

    private static AuditEvent event(AppId app, SessionId session, UserId user, RunId run,
                                    AuditKind kind, String summary, Map<String, Object> details) {
        return new AuditEvent(Instant.now(), app, run, session, user, kind, summary, details);
    }

    /** A scripted turn: the answer to return plus the tool calls / cited sources to emit as audit. */
    private record Plan(AgentAnswer answer, List<ToolCall> toolCalls, List<String> sources, RuntimeException fault) {
    }

    /**
     * An {@link AgentService} that answers each prompt from {@code scripts} and emits the same audit
     * trail a real agent would (MODEL_CALL, a TOOL_CALL per tool with name+args, a RETRIEVAL per
     * cited source set, DECISION) into {@code sink} — the ground truth the harness reconstructs from.
     */
    private record ScriptedGoldenAgentService(Map<String, Function<RunId, Plan>> scripts, RecordingAuditSink sink)
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
                        throw new IllegalStateException("no golden script for prompt: " + msg.text());
                    }
                    Plan plan = factory.apply(run);

                    List<AuditEvent> emitted = new ArrayList<>();
                    emitted.add(event(app, session, user, run, AuditKind.MODEL_CALL, "model: stub", Map.of()));
                    for (ToolCall tc : plan.toolCalls()) {
                        emitted.add(event(app, session, user, run, AuditKind.TOOL_CALL, "tool: " + tc.toolName(),
                                Map.of("tool", tc.toolName(), "args", tc.arguments())));
                    }
                    if (!plan.sources().isEmpty()) {
                        emitted.add(event(app, session, user, run, AuditKind.RETRIEVAL,
                                "retrieved " + plan.sources().size() + " source(s)",
                                Map.of("sourceIds", plan.sources())));
                    }
                    if (plan.fault() != null) {
                        emitted.add(event(app, session, user, run, AuditKind.ERROR, "run failed", Map.of()));
                        emitted.forEach(sink::record);
                        throw plan.fault();
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
