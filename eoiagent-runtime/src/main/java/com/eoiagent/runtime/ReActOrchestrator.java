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
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.scratchpad.Scratchpad;
import com.eoiagent.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The default/fallback {@link Orchestrator}: a bounded reason+act loop (Flow B). Each iteration asks
 * the {@link LlmGateway} for the next move given the conversation so far and the tools the context
 * may see; a tool call is dispatched through the audited {@link ToolRegistry} and its (possibly
 * large) result is offloaded to the {@link Scratchpad} before being summarised back into history; a
 * final answer ends the run. The loop is hard-bounded by {@code eoiagent.runtime.maxSteps} and never
 * loops unbounded.
 *
 * <p>Phase 1 is read-only (Flow B). Planner-driven plan→approve→act (Flow C), supervisor/sub-agents
 * (Flow D) and checkpointed investigation (Flow E) are later phases; this orchestrator reaches the
 * model, tools and scratchpad only through their ports, never a concrete library type.
 */
public final class ReActOrchestrator implements Orchestrator {

    private final LlmGateway gateway;
    private final ToolRegistry tools;
    private final Scratchpad scratchpad;
    private final AuditSink audit;
    private final ConfigProvider config;

    public ReActOrchestrator(LlmGateway gateway, ToolRegistry tools, Scratchpad scratchpad,
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
            return loop(goal, ctx, run);
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (RuntimeException e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The run failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, emptyTasks(), List.of(), 0);
        }
    }

    private AgentRun loop(Goal goal, AgentContext ctx, RunId run) {
        int maxSteps = config.get(RuntimeConfigKeys.MAX_STEPS);
        int offloadThreshold = config.get(RuntimeConfigKeys.OFFLOAD_THRESHOLD_BYTES);
        List<ChatMessageRecord> history = new ArrayList<>();
        history.add(message(ChatRole.USER, goal.text()));
        List<ToolSpec> visible = tools.visibleTo(ctx);

        int steps = 0;
        while (steps < maxSteps) {
            steps++;
            ChatResult result = gateway.chat(new ChatRequest(history, visible, ChatOptions.defaults()));
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "model: " + modelName(result.model())));

            List<ToolCall> calls = result.toolCalls();
            if (calls == null || calls.isEmpty()) {
                audit.record(event(ctx, run, AuditKind.DECISION, "final answer"));
                AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, result.text(), null, null, List.of(), run);
                return new AgentRun(run, answer, emptyTasks(), List.of(), steps);
            }

            for (ToolCall call : calls) {
                ToolCall scoped = new ToolCall(call.toolName(), call.arguments(), run);
                ToolResult toolResult = tools.dispatch(scoped, ctx); // audited TOOL_CALL inside dispatch
                history.add(message(ChatRole.TOOL, observe(toolResult, run, offloadThreshold)));
            }
        }

        // maxSteps exhausted — conclude gracefully, never throw or loop unbounded (spec §2.3).
        audit.record(event(ctx, run, AuditKind.DECISION, "max steps (" + maxSteps + ") reached"));
        AgentAnswer answer = new AgentAnswer(AnswerKind.CLARIFICATION,
                "I couldn't complete this within " + maxSteps + " steps. Could you narrow the request?",
                null, null, List.of(), run);
        return new AgentRun(run, answer, emptyTasks(), List.of(), steps);
    }

    /** Inlines a small tool result, or offloads a large one to the scratchpad and returns a synopsis. */
    private String observe(ToolResult result, RunId run, int offloadThreshold) {
        String value = result.ok() ? String.valueOf(result.value()) : "tool error: " + result.error();
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (result.ok() && bytes > offloadThreshold) {
            String handle = scratchpad.write(run.value() + "/tool/" + UUID.randomUUID(), value);
            return "[tool result stored at " + handle + " — " + bytes + " bytes; read it to inspect]";
        }
        return value;
    }

    private static ChatMessageRecord message(ChatRole role, String text) {
        return new ChatMessageRecord(role, text == null ? "" : text, Instant.now(), Map.of());
    }

    private static String modelName(ModelInfo info) {
        return info == null ? "unknown" : info.provider() + "/" + info.modelId();
    }

    private static TaskList emptyTasks() {
        return new TaskList(List.<com.eoiagent.core.Task>of()); // ReAct (Flow B) has no planned task list
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, Map.<String, Object>of());
    }
}
