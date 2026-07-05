package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Goal;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.observability.AuditSink;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The evaluator-critic {@link Orchestrator} (reflection loop, T-500): the agent
 * <em>drafts</em> an answer, an LLM <em>critic</em> reviews it, and the agent <em>revises</em>
 * until the critic approves or the revision budget is spent — the "draft → review → fix"
 * refinement the original design named as a core piece and a stated reason for the stateful-graph
 * engine. It targets the agent's signature generation goals ({@code SQL_GEN}, {@code PIPELINE_AUTHOR}),
 * where a self-critique pass materially improves correctness, but is goal-agnostic: it reflects on
 * whatever {@link Goal} it is given.
 *
 * <p>Like {@link ReActOrchestrator} it is our own bounded loop — no external orchestration engine —
 * and reaches the model only through the {@link LlmGateway} port. Every draft, critique and revise
 * is one model call ({@code MODEL_CALL}); each round's outcome is a {@code DECISION}. The loop is
 * hard-bounded by {@code eoiagent.runtime.reflection.maxRevisions} and never revises unbounded.
 *
 * <p>Offline fail-closed (AC11): the adapter performs no network call itself; reachability is the
 * {@code LlmGateway} port's concern under the active {@link com.eoiagent.core.DeploymentProfile}.
 * Orchestrator selection (which goals route here) is a wiring concern and stays out of this adapter.
 */
public final class ReflectionOrchestrator implements Orchestrator {

    /** A critic reply is an approval when its verdict — leading, case-insensitive — is APPROVED. */
    private static final String APPROVED = "APPROVED";

    private final LlmGateway gateway;
    private final AuditSink audit;
    private final ConfigProvider config;

    public ReflectionOrchestrator(LlmGateway gateway, AuditSink audit, ConfigProvider config) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            return reflect(goal, ctx, run);
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (RuntimeException e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The run failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, emptyTasks(), List.of(), 0);
        }
    }

    private AgentRun reflect(Goal goal, AgentContext ctx, RunId run) {
        int maxRevisions = Math.max(0, config.get(RuntimeConfigKeys.REFLECTION_MAX_REVISIONS));

        String draft = ask(draftPrompt(goal)).text();
        audit.record(event(ctx, run, AuditKind.MODEL_CALL, "reflection: draft"));
        audit.record(event(ctx, run, AuditKind.DECISION, "reflection: drafted"));
        int modelCalls = 1;

        int revisions = 0;
        while (true) {
            String critique = ask(critiquePrompt(goal, draft)).text();
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "reflection: critique"));
            modelCalls++;

            if (approved(critique)) {
                audit.record(event(ctx, run, AuditKind.DECISION,
                        "reflection round " + (revisions + 1) + ": approved"));
                break;
            }
            if (revisions >= maxRevisions) {
                audit.record(event(ctx, run, AuditKind.DECISION,
                        "reflection: max revisions (" + maxRevisions + ") reached"));
                break;
            }

            audit.record(event(ctx, run, AuditKind.DECISION,
                    "reflection round " + (revisions + 1) + ": revising"));
            draft = ask(revisePrompt(goal, draft, critique)).text();
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "reflection: revise"));
            modelCalls++;
            revisions++;
        }

        AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, blankToDefault(draft, ""),
                null, null, List.of(), run);
        return new AgentRun(run, answer, emptyTasks(), List.of(), modelCalls);
    }

    private ChatResult ask(String prompt) {
        List<ChatMessageRecord> history =
                List.of(new ChatMessageRecord(ChatRole.USER, prompt, Instant.now(), Map.of()));
        return gateway.chat(new ChatRequest(history, List.of(), ChatOptions.defaults()));
    }

    private static String draftPrompt(Goal goal) {
        return "Produce your best answer for the following task. Return only the answer.\n\nTask: "
                + goal.text();
    }

    private static String critiquePrompt(Goal goal, String draft) {
        return "You are a strict reviewer. Review the draft answer to the task below. If it is correct"
                + " and complete, reply with exactly APPROVED. Otherwise reply with specific, actionable"
                + " revisions — do not rewrite it yourself.\n\nTask: " + goal.text()
                + "\n\nDraft:\n" + blankToDefault(draft, "(empty)");
    }

    private static String revisePrompt(Goal goal, String draft, String critique) {
        return "Revise the draft to address the reviewer's feedback. Return only the improved answer."
                + "\n\nTask: " + goal.text()
                + "\n\nDraft:\n" + blankToDefault(draft, "(empty)")
                + "\n\nReviewer feedback:\n" + blankToDefault(critique, "(none)");
    }

    /** The critic approves when the verdict leads with APPROVED — tolerant of trailing text/punctuation. */
    private static boolean approved(String critique) {
        if (critique == null) {
            return false;
        }
        return critique.strip().toUpperCase(Locale.ROOT).startsWith(APPROVED);
    }

    private static String blankToDefault(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static TaskList emptyTasks() {
        return new TaskList(List.<com.eoiagent.core.Task>of()); // reflection refines one answer; no task list
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, Map.<String, Object>of());
    }
}
