package com.eoiagent.eval;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.Capability;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.Feature;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.persistence.Checkpoint;
import com.eoiagent.persistence.InMemoryCheckpointStore;
import com.eoiagent.runtime.AgentRun;
import com.eoiagent.runtime.LangGraphOrchestrator;
import com.eoiagent.safety.ApprovalGate;
import com.eoiagent.safety.PolicyEngine;
import com.eoiagent.tool.DefaultToolRegistry;
import com.eoiagent.tool.InvestigationApi;
import com.eoiagent.tool.JavaApiTool;
import com.eoiagent.tool.PlaybookApi;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-306 — the Phase-3 eval: investigation scenarios + restart→resume.
 *
 * <p>The golden suite runs OFFLINE against the <strong>real</strong> T-304 investigation tools
 * ({@link InvestigationApi}/{@link PlaybookApi}) dispatched through the real read-only
 * {@link DefaultToolRegistry}: answers quote actual tool output and the harness reconstructs tool
 * calls from the registry's own {@code TOOL_CALL} audit events. The restart→resume test crashes a
 * real {@link LangGraphOrchestrator} run mid-investigation, then resumes it with a <strong>new
 * orchestrator instance</strong> over the same {@link InMemoryCheckpointStore} — proving the
 * Phase-3 exit criterion that an investigation survives a process restart (roadmap §Phase 3).
 */
class Phase3EvalRunnerTest {

    // ── Investigation golden suite over the real T-304 tools ─────────────────────────────────────

    @TestFactory
    Stream<DynamicTest> phase3InvestigationSuiteRunsGreenOffline() {
        EvalSuite suite = loadSuite();
        RecordingAuditSink sink = new RecordingAuditSink();
        AgentService agent = new InvestigationAgentService(sink);

        EvalReport report = new DefaultEvalHarness(new CompositeScorer(), sink::events)
                .run(suite, agent, DeploymentProfile.OFFLINE);

        assertThat(report.total()).isEqualTo(suite.cases().size());

        return report.outcomes().stream().map(o ->
                DynamicTest.dynamicTest(o.case_().id(), () ->
                        assertThat(o.score().pass())
                                .as("case '%s': %s", o.case_().id(), o.score().detail())
                                .isTrue()));
    }

    // ── Restart → resume (Phase-3 exit criterion) ─────────────────────────────────────────────────

    @Test
    void investigationSurvivesARestartAndResumesFromItsLatestCheckpoint() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore(); // survives across "restart"
        Goal goal = new Goal("why is the orders_daily pipeline failing?", GoalKind.INVESTIGATION);
        AgentContext ctx = new AgentContext(new AppId("eval-app"), new SessionId("s"),
                new UserId("u"), Role.SUPPORT, DeploymentProfile.OFFLINE, null, Map.of());

        // Process 1: gather + hypothesize succeed, then the "process" dies at testHypothesis.
        QueueGateway crashing = new QueueGateway(
                List.of("repeated schema-validation failures on curated.orders",
                        "schema drift on the discount column broke the write"),
                new RuntimeException("simulated process crash"));
        AgentRun crashed = new LangGraphOrchestrator(crashing, new RecordingAuditSink(),
                new DefaultsConfig(), store, new ApprovedGate()).run(goal, ctx);

        assertThat(crashed.answer().kind()).isEqualTo(AnswerKind.ERROR);
        assertThat(store.history(crashed.id())).extracting(Checkpoint::nodeId)
                .as("completed nodes must be durably checkpointed before the crash")
                .containsExactly("gatherSignals", "hypothesize");

        // Process 2 ("after restart"): a brand-new orchestrator + gateway over the SAME store.
        QueueGateway fresh = new QueueGateway(List.of("CONFIRMED"), null);
        AgentRun resumed = new LangGraphOrchestrator(fresh, new RecordingAuditSink(),
                new DefaultsConfig(), store, new ApprovedGate()).resume(crashed.id(), goal, ctx);

        assertThat(resumed.id()).isEqualTo(crashed.id());                       // same run, not a new one
        assertThat(resumed.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(resumed.answer().text()).contains("schema drift");           // hypothesis restored from state
        assertThat(fresh.chatCalls).isEqualTo(1);                               // gather+hypothesize NOT re-run
        assertThat(store.history(crashed.id())).extracting(Checkpoint::nodeId)
                .containsExactly("gatherSignals", "hypothesize", "testHypothesis", "conclude");
    }

    // ── The scripted investigation service: real tools, real registry, real audit ────────────────

    /**
     * Opens sessions whose {@code ask} dispatches the REAL investigation tools through a read-only
     * {@link DefaultToolRegistry} (emitting genuine {@code TOOL_CALL} audit events) and composes the
     * answer from the actual tool results — the "schema drift" root cause in the final answer exists
     * only because the real {@code listEvents} corpus contains it.
     */
    private record InvestigationAgentService(RecordingAuditSink sink) implements AgentService {

        @Override
        public AgentSession open(SessionRequest req) {
            AppId app = new AppId("eval-app");
            SessionId session = new SessionId(UUID.randomUUID().toString());
            DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicy(), sink);
            registerInvestigationTools(registry);
            AgentContext ctx = new AgentContext(app, session, req.user(), req.role(),
                    req.profile(), req.initialPage(), Map.of());

            return new AgentSession() {
                @Override
                public AgentAnswer ask(UserMessage msg) {
                    RunId run = new RunId(UUID.randomUUID().toString());
                    String text = switch (msg.text()) {
                        case "Investigate why the orders_daily pipeline is failing." -> {
                            dispatch(registry, ctx, run, "getPlaybook", Map.of("issueKind", "pipeline-failure"));
                            String events = dispatch(registry, ctx, run, "listEvents", Map.of("componentId", "orders_daily"));
                            String alerts = dispatch(registry, ctx, run, "listAlerts", Map.of("severity", "HIGH"));
                            String incident = dispatch(registry, ctx, run, "getIncident", Map.of("incidentId", "INC-2001"));
                            yield events.contains("schema drift") && incident.contains("orders_daily")
                                    ? "Root cause: schema drift on orders_daily — a source column type change is "
                                      + "failing schema validation (incident INC-2001, alert "
                                      + (alerts.contains("A-101") ? "A-101" : "unknown") + ")."
                                    : "Investigation inconclusive from the available signals.";
                        }
                        case "What is the playbook for a pipeline failure?" ->
                                "Playbook: " + dispatch(registry, ctx, run, "getPlaybook",
                                        Map.of("issueKind", "pipeline-failure"));
                        case "Which high-severity alerts are active right now?" ->
                                "Active high-severity alerts: " + dispatch(registry, ctx, run, "listAlerts",
                                        Map.of("severity", "HIGH"));
                        default -> throw new IllegalStateException("no phase3 script for prompt: " + msg.text());
                    };
                    sink.record(new AuditEvent(Instant.now(), app, run, session, req.user(),
                            com.eoiagent.core.AuditKind.DECISION, "final answer", Map.of()));
                    return new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), run);
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

        private static String dispatch(DefaultToolRegistry registry, AgentContext ctx, RunId run,
                                       String tool, Map<String, Object> args) {
            ToolResult result = registry.dispatch(new ToolCall(tool, args, run), ctx);
            assertThat(result.ok()).as("tool %s must succeed", tool).isTrue();
            return String.valueOf(result.value());
        }

        private static void registerInvestigationTools(DefaultToolRegistry registry) {
            try {
                InvestigationApi investigation = new InvestigationApi();
                for (String method : List.of("listEvents", "listAlerts", "getIncident", "listCases")) {
                    registry.register(new JavaApiTool(investigation,
                            InvestigationApi.class.getDeclaredMethod(method, String.class),
                            false, Role.SUPPORT, Capability.INVESTIGATE));
                }
                registry.register(new JavaApiTool(new PlaybookApi(),
                        PlaybookApi.class.getDeclaredMethod("getPlaybook", String.class),
                        false, Role.SUPPORT, Capability.INVESTIGATE));
            } catch (NoSuchMethodException e) {
                throw new AssertionError("T-304 tool method missing", e);
            }
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────────

    private static EvalSuite loadSuite() {
        try (InputStream in = Phase3EvalRunnerTest.class.getResourceAsStream("/eval/phase3-golden/golden.yaml")) {
            return YamlEvalCaseLoader.load(in);
        } catch (Exception e) {
            throw new AssertionError("failed to load phase3 golden suite", e);
        }
    }

    /** Deterministic {@link LlmGateway}: replies from a queue, then (optionally) crashes. */
    private static final class QueueGateway implements LlmGateway {
        private static final ModelInfo MODEL = new ModelInfo("scripted", "stub", true);
        private final Deque<String> replies;
        private final RuntimeException afterQueue;
        int chatCalls = 0;

        QueueGateway(List<String> replies, RuntimeException afterQueue) {
            this.replies = new ArrayDeque<>(replies);
            this.afterQueue = afterQueue;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            chatCalls++;
            if (replies.isEmpty()) {
                throw afterQueue != null ? afterQueue : new IllegalStateException("script exhausted");
            }
            return new ChatResult(replies.pop(), List.of(), MODEL, null);
        }

        @Override
        public void chatStream(ChatRequest request, TokenSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelInfo activeChatModel() {
            return MODEL;
        }

        @Override
        public boolean isAvailable(ModelRole role) {
            return true;
        }
    }

    /** OFFLINE config: every key at its default, every feature on (checkpointing enabled). */
    private static final class DefaultsConfig implements ConfigProvider {
        @Override
        public DeploymentProfile profile() {
            return DeploymentProfile.OFFLINE;
        }

        @Override
        public <T> T get(ConfigKey<T> key) {
            return key.defaultValue();
        }

        @Override
        public boolean featureEnabled(Feature feature) {
            return true;
        }
    }

    /** Approves every escalation; investigation tools are read-only so the gate is never consulted by dispatch. */
    private static final class ApprovedGate implements ApprovalGate {
        @Override
        public ApprovalDecision request(ApprovalRequest req) {
            return ApprovalDecision.APPROVED;
        }

        @Override
        public DryRunResult dryRun(ToolCall call) {
            return new DryRunResult(true, "no-op preview", Map.of());
        }
    }

    /** Allows every capability; enforces nothing — visibility is not under test here. */
    private static final class AllowAllPolicy implements PolicyEngine {
        @Override
        public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
            return true;
        }

        @Override
        public void check(AgentContext ctx, ToolSpec tool) {
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
