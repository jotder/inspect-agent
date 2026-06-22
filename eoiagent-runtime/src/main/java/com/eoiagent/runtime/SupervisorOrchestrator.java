package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Goal;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.scratchpad.Scratchpad;
import com.eoiagent.tool.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The supervisor {@link Orchestrator} (Flow D, spec §4): an LLM supervisor repeatedly picks a worker
 * by sub-goal; each worker runs as an <strong>isolated nested orchestration</strong> with a strictly
 * narrowed tool subset ({@link WorkerToolView}) and its own {@link Scratchpad} scope
 * ({@link ScopedScratchpad}), so workers cannot see one another's tools or scratchpad. The supervisor
 * aggregates the workers' results into one final {@link AgentAnswer}. Delegation is hard-bounded by
 * {@code eoiagent.runtime.supervisor.maxWorkers}.
 *
 * <p>Reaches the model, tools and scratchpad only through their ports — never a concrete library
 * type — and reuses {@link ReActOrchestrator} as the per-worker engine. The supervisor's choice is
 * read from the model's returned action: the tool name names the worker to delegate to; a final text
 * (or an unknown worker) concludes the run.
 */
public final class SupervisorOrchestrator implements Orchestrator {

    /** A worker: a name the supervisor delegates to and the capability subset it may use. */
    private record WorkerSpec(String name, Set<Capability> capabilities) {
    }

    /** The fixed Phase-2 worker catalogue (spec §4.1): analysis, SQL, pipeline. */
    private static final List<WorkerSpec> WORKERS = List.of(
            new WorkerSpec("analysis", Set.of(
                    Capability.READ_METADATA, Capability.READ_SCHEMA, Capability.READ_DOCS)),
            new WorkerSpec("sql", Set.of(
                    Capability.GENERATE_SQL, Capability.RUN_SQL_READONLY)),
            new WorkerSpec("pipeline", Set.of(
                    Capability.AUTHOR_PIPELINE, Capability.RUN_PIPELINE)));

    private final LlmGateway gateway;
    private final ToolRegistry tools;
    private final Scratchpad scratchpad;
    private final AuditSink audit;
    private final ConfigProvider config;

    public SupervisorOrchestrator(LlmGateway gateway, ToolRegistry tools, Scratchpad scratchpad,
                                  AuditSink audit, ConfigProvider config) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.scratchpad = Objects.requireNonNull(scratchpad, "scratchpad");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            return supervise(goal, ctx, run);
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (RuntimeException e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The run failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, emptyTasks(), List.of(), 0);
        }
    }

    private AgentRun supervise(Goal goal, AgentContext ctx, RunId run) {
        int maxWorkers = config.get(RuntimeConfigKeys.SUPERVISOR_MAX_WORKERS);
        List<ToolSpec> visible = tools.visibleTo(ctx);
        List<ChatMessageRecord> history = new ArrayList<>();
        history.add(message(ChatRole.USER, goal.text()));
        List<String> findings = new ArrayList<>();
        String concluding = null;

        int delegations = 0;
        while (delegations < maxWorkers) {
            ChatResult choice = gateway.chat(new ChatRequest(history, visible, ChatOptions.defaults()));
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "supervisor: choose next worker"));

            List<ToolCall> calls = choice.toolCalls();
            WorkerSpec worker = (calls == null || calls.isEmpty()) ? null : resolve(calls.get(0).toolName());
            if (worker == null) {
                concluding = choice.text(); // final text or an unknown worker — supervisor concludes
                break;
            }

            audit.record(event(ctx, run, AuditKind.DECISION, "delegate to " + worker.name()));
            String result = runWorker(worker, goal, ctx, run);
            findings.add(worker.name() + ": " + result);
            history.add(message(ChatRole.ASSISTANT, "[" + worker.name() + " result] " + result));
            delegations++;
        }

        audit.record(event(ctx, run, AuditKind.DECISION,
                "aggregate (" + delegations + " worker" + (delegations == 1 ? "" : "s") + ")"));
        String text = (concluding != null && !concluding.isBlank()) ? concluding : aggregate(findings);
        AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), run);
        return new AgentRun(run, answer, emptyTasks(), List.of(), delegations);
    }

    /** Runs one worker as an isolated nested ReAct run: narrowed tool view + scoped scratchpad. */
    private String runWorker(WorkerSpec worker, Goal goal, AgentContext ctx, RunId run) {
        ToolRegistry view = new WorkerToolView(tools, worker.capabilities());
        Scratchpad scoped = new ScopedScratchpad(scratchpad, run.value() + "/worker/" + worker.name() + "/");
        ReActOrchestrator workerRun = new ReActOrchestrator(gateway, view, scoped, audit, config);
        return workerRun.run(goal, ctx).answer().text();
    }

    private static WorkerSpec resolve(String name) {
        if (name == null) {
            return null;
        }
        for (WorkerSpec w : WORKERS) {
            if (w.name().equalsIgnoreCase(name)) {
                return w;
            }
        }
        return null;
    }

    private static String aggregate(List<String> findings) {
        if (findings.isEmpty()) {
            return "No workers were delegated; nothing to report.";
        }
        return "Aggregated findings from " + findings.size() + " worker(s):\n" + String.join("\n", findings);
    }

    private static ChatMessageRecord message(ChatRole role, String text) {
        return new ChatMessageRecord(role, text == null ? "" : text, Instant.now(), Map.of());
    }

    private static TaskList emptyTasks() {
        return new TaskList(List.<com.eoiagent.core.Task>of());
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, Map.<String, Object>of());
    }
}
