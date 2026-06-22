package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Default {@link EvalHarness} that opens a session per case, asks the prompt and scores the answer. */
public final class DefaultEvalHarness implements EvalHarness {

    private final Scorer scorer;
    private final Supplier<List<AuditEvent>> auditTrail;

    /** Creates a harness using a {@link CompositeScorer} and an empty audit trail. */
    public DefaultEvalHarness() {
        this(new CompositeScorer(), List::of);
    }

    /** Creates a harness using the given scorer and an empty audit trail. */
    public DefaultEvalHarness(Scorer scorer) {
        this(scorer, List::of);
    }

    /**
     * Creates a harness that reconstructs each case's tool calls and cited source ids from the audit
     * stream supplied by {@code auditTrail} — typically a {@code RecordingAuditSink} wired into the
     * agent under test. Events are matched to a case by the run id of its answer, so the harness
     * observes the same {@code TOOL_CALL}/{@code RETRIEVAL} record that compliance does.
     */
    public DefaultEvalHarness(Scorer scorer, Supplier<List<AuditEvent>> auditTrail) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @Override
    public EvalReport run(EvalSuite suite, AgentService agent, DeploymentProfile profile) {
        List<CaseOutcome> outcomes = new ArrayList<>();
        for (EvalCase c : suite.cases()) {
            outcomes.add(runCase(c, agent, profile));
        }
        int total = suite.cases().size();
        int passed = 0;
        for (CaseOutcome outcome : outcomes) {
            if (outcome.score().pass()) {
                passed++;
            }
        }
        int failed = total - passed;
        return new EvalReport(suite.name(), profile, total, passed, failed, List.copyOf(outcomes), Instant.now());
    }

    private CaseOutcome runCase(EvalCase c, AgentService agent, DeploymentProfile profile) {
        AgentSession session = null;
        try {
            session = agent.open(new SessionRequest(new UserId("eval"), c.role(), profile, c.page(), Map.of()));
            AgentAnswer answer = session.ask(new UserMessage(c.prompt(), c.page(), Instant.now()));
            List<AuditEvent> trail = auditTrail.get();
            EvalRunResult actual = new EvalRunResult(answer,
                    reconstructToolCalls(trail, answer.run()),
                    reconstructCitedSourceIds(trail, answer.run()),
                    answer.run());
            Score score = scorer.score(c, actual);
            return new CaseOutcome(c, score, actual);
        } catch (RuntimeException ex) {
            Score score;
            if (c.expect().expectedKind() == AnswerKind.ERROR) {
                score = new Score(true, 1.0, "expected error: " + ex);
            } else {
                score = new Score(false, 0.0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            return new CaseOutcome(c, score, null);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Rebuilds a run's tool calls from its {@code TOOL_CALL} and {@code MUTATION} events
     * ({@code details.tool}/{@code details.args}). A read-only tool is audited {@code TOOL_CALL}; a
     * mutating tool's execution is audited {@code MUTATION} (post-approval, Flow C) — both are tool
     * invocations the harness must surface so a case can assert mutating behaviour.
     */
    private static List<ToolCall> reconstructToolCalls(List<AuditEvent> events, RunId run) {
        List<ToolCall> calls = new ArrayList<>();
        for (AuditEvent e : events) {
            if ((e.kind() != AuditKind.TOOL_CALL && e.kind() != AuditKind.MUTATION)
                    || !Objects.equals(e.run(), run)) {
                continue;
            }
            Map<String, Object> details = e.details() == null ? Map.of() : e.details();
            String name = details.get("tool") == null ? null : String.valueOf(details.get("tool"));
            Map<String, Object> args = details.get("args") instanceof Map<?, ?> raw ? toStringKeys(raw) : Map.of();
            calls.add(new ToolCall(name, args, run));
        }
        return List.copyOf(calls);
    }

    /** Rebuilds a run's cited source ids from its {@code RETRIEVAL} events ({@code details.sourceId(s)}). */
    private static List<String> reconstructCitedSourceIds(List<AuditEvent> events, RunId run) {
        List<String> ids = new ArrayList<>();
        for (AuditEvent e : events) {
            if (e.kind() != AuditKind.RETRIEVAL || !Objects.equals(e.run(), run)) {
                continue;
            }
            Map<String, Object> details = e.details() == null ? Map.of() : e.details();
            Object single = details.get("sourceId");
            if (single != null) {
                ids.add(String.valueOf(single));
            }
            if (details.get("sourceIds") instanceof List<?> many) {
                for (Object id : many) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        return List.copyOf(ids);
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(out);
    }
}
