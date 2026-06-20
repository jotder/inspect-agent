package com.eoiagent.runtime;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Goal;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.observability.AuditSink;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link Orchestrator} backed by {@code langchain4j-agentic} — the EXPERIMENTAL workflow engine,
 * quarantined to this adapter module per ADR-0010 (no {@code dev.langchain4j.agentic} type appears
 * in core or any non-adapter package). The agentic engine drives an LangChain4j {@link ChatModel}
 * directly (it cannot route through our {@code LlmGateway} port), so this adapter is constructed from
 * the same model the host wires for hosted/local chat.
 *
 * <p>Phase 1 builds a single declarative agent answering a QA goal — enough to establish and exercise
 * the quarantined dependency. The richer sequential / parallel / conditional / supervisor workflows
 * (Flows C and D) are Phase 2; they slot in behind this same port without touching core or the host.
 */
public final class AgenticOrchestrator implements Orchestrator {

    /** Minimal declarative agent: answer the user's question. */
    public interface QaAgent {
        @Agent(value = "Answer the user's question concisely", outputKey = "answer")
        @UserMessage("{{question}}")
        String answer(@V("question") String question);
    }

    private final QaAgent agent;
    private final AuditSink audit;

    public AgenticOrchestrator(ChatModel chatModel, AuditSink audit) {
        Objects.requireNonNull(chatModel, "chatModel");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.agent = AgenticServices.agentBuilder(QaAgent.class).chatModel(chatModel).build();
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "agentic: answer"));
            String text = agent.answer(goal.text());
            audit.record(event(ctx, run, AuditKind.DECISION, "final answer"));
            AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), run);
            return new AgentRun(run, answer, new TaskList(List.of()), List.of(), 1);
        } catch (RuntimeException e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The run failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, new TaskList(List.of()), List.of(), 0);
        }
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, Map.<String, Object>of());
    }
}
