package com.eoiagent.host;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.RunId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.runtime.AgentRun;
import com.eoiagent.runtime.Orchestrator;
import com.eoiagent.safety.Guardrail;
import com.eoiagent.safety.GuardrailInput;
import com.eoiagent.safety.GuardrailPhase;

import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.GuardrailVerdict;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One live session (Flow A). Each {@code ask} refreshes the {@link PageContext} from the message,
 * runs the input {@link Guardrail}, delegates to the {@link Orchestrator}, and returns the run's
 * typed {@link AgentAnswer} (TEXT / INLINE_ARTIFACT / NAVIGATION / CLARIFICATION). Runtime faults are
 * caught and surfaced as {@code AgentAnswer(ERROR)} — {@code ask} never throws past this boundary —
 * and every ask records at least one audit event. {@code askStream} runs the same logic and then
 * streams the answer text token-by-token to the host's {@link AnswerSink}.
 *
 * <p>Single-threaded from the caller's perspective; concurrent asks on one session are undefined.
 */
final class DefaultAgentSession implements AgentSession {

    private final AgentContext baseCtx;
    private final Orchestrator orchestrator;
    private final Guardrail inputGuardrail;
    private final AuditSink audit;
    private volatile boolean closed;

    DefaultAgentSession(AgentContext baseCtx, Orchestrator orchestrator, Guardrail inputGuardrail, AuditSink audit) {
        this.baseCtx = baseCtx;
        this.orchestrator = orchestrator;
        this.inputGuardrail = inputGuardrail;
        this.audit = audit;
    }

    @Override
    public AgentAnswer ask(UserMessage msg) {
        ensureOpen();
        Objects.requireNonNull(msg, "msg");
        try {
            return coreRun(msg);
        } catch (RuntimeException e) {
            return errorAnswer("ask failed: " + e.getMessage());
        }
    }

    @Override
    public void askStream(UserMessage msg, AnswerSink sink) {
        ensureOpen();
        Objects.requireNonNull(msg, "msg");
        Objects.requireNonNull(sink, "sink");
        AgentAnswer answer;
        try {
            answer = coreRun(msg);
        } catch (RuntimeException e) {
            sink.onError(asEoiException(e));
            return;
        }
        String text = answer.text() == null ? "" : answer.text();
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty()) {
                sink.onToken(token);
            }
        }
        if (answer.artifact() != null) {
            sink.onArtifact(answer.artifact());
        }
        sink.onComplete(answer);
    }

    @Override
    public void close() {
        closed = true; // idempotent; per-session memory flush is wired in a later phase
    }

    /** Guardrail → orchestrator → typed answer + a DECISION audit. Genuine faults propagate. */
    private AgentAnswer coreRun(UserMessage msg) {
        AgentContext ctx = withPage(msg.page());
        GuardrailResult guard = inputGuardrail.check(new GuardrailInput(GuardrailPhase.INPUT, msg.text(), null, ctx));
        if (guard.verdict() == GuardrailVerdict.FAIL) {
            RunId run = newRun();
            record(AuditKind.DECISION, run, "input guardrail blocked: " + safe(guard.message()));
            return new AgentAnswer(AnswerKind.ERROR, "Your request was blocked by a safety check.",
                    null, null, List.of(), run);
        }
        String text = guard.verdict() == GuardrailVerdict.REDACTED ? guard.transformedText() : msg.text();

        AgentRun run = orchestrator.run(new Goal(text, GoalKind.QA), ctx);
        AgentAnswer answer = run.answer();
        String redactNote = guard.verdict() == GuardrailVerdict.REDACTED ? " (pii redacted)" : "";
        record(AuditKind.DECISION, run.id(), "ask -> " + answer.kind() + redactNote);
        return answer;
    }

    private AgentAnswer errorAnswer(String detail) {
        RunId run = newRun();
        record(AuditKind.ERROR, run, detail);
        return new AgentAnswer(AnswerKind.ERROR, "The request could not be completed.",
                null, null, List.of(), run);
    }

    private AgentContext withPage(PageContext page) {
        return new AgentContext(baseCtx.app(), baseCtx.session(), baseCtx.user(), baseCtx.role(),
                baseCtx.profile(), page, baseCtx.attributes());
    }

    private void record(AuditKind kind, RunId run, String summary) {
        audit.record(new AuditEvent(Instant.now(), baseCtx.app(), run, baseCtx.session(), baseCtx.user(),
                kind, summary, Map.<String, Object>of()));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("session is closed");
        }
    }

    private static RunId newRun() {
        return new RunId(UUID.randomUUID().toString());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static EoiAgentException asEoiException(RuntimeException e) {
        return e instanceof EoiAgentException eoi ? eoi : new EoiAgentException(e.getMessage(), e);
    }
}
