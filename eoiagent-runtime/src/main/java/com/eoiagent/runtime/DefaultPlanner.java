package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Plan;
import com.eoiagent.core.PlanStep;
import com.eoiagent.core.TaskId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The default {@link Planner}: turns a {@link Goal} into a typed {@link Plan}.
 *
 * <p>A pure-{@link GoalKind#QA QA} goal is a single non-mutating pass-through step (no model call,
 * so it is trivially OFFLINE-safe). For any other goal the planner asks the {@link LlmGateway} for
 * the moves it would make given the tools visible to the context, and turns each returned
 * {@link ToolCall} into a {@link PlanStep}. A step's {@code mutating} flag is <em>derived from the
 * registry</em> — it is {@code true} iff the named tool's {@link ToolSpec#mutating()} is true — never
 * from whatever the model claims (spec contract for {@code Planner.plan}). Plans reach the model and
 * the tool catalogue only through their ports, never a concrete library type.
 */
public final class DefaultPlanner implements Planner {

    private final LlmGateway gateway;
    private final com.eoiagent.tool.ToolRegistry tools;

    public DefaultPlanner(LlmGateway gateway, com.eoiagent.tool.ToolRegistry tools) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public Plan plan(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");

        if (goal.kind() == GoalKind.QA) {
            PlanStep only = new PlanStep(new TaskId("step-1"), goal.text(), false);
            return new Plan(List.of(only), "Answer the question directly.");
        }

        List<ToolSpec> visible = tools.visibleTo(ctx);
        ChatResult result = gateway.chat(new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.USER, goal.text(), Instant.now(), Map.of())),
                visible, ChatOptions.defaults()));

        List<ToolCall> calls = result.toolCalls();
        if (calls == null || calls.isEmpty()) {
            // The planner sees no tool to use — a single non-mutating "answer directly" step.
            PlanStep only = new PlanStep(new TaskId("step-1"),
                    result.text() == null || result.text().isBlank() ? goal.text() : result.text(), false);
            return new Plan(List.of(only), "Answer directly; no tool step required.");
        }

        Map<String, Boolean> mutatingByName = new java.util.HashMap<>();
        for (ToolSpec spec : visible) {
            mutatingByName.put(spec.name(), spec.mutating());
        }

        List<PlanStep> steps = new ArrayList<>(calls.size());
        int n = 0;
        for (ToolCall call : calls) {
            boolean mutating = mutatingByName.getOrDefault(call.toolName(), false);
            steps.add(new PlanStep(new TaskId("step-" + (++n)),
                    "Call tool '" + call.toolName() + "'", mutating));
        }
        return new Plan(List.copyOf(steps), "Execute " + steps.size() + " step(s) to reach the goal.");
    }

    @Override
    public Plan revise(Plan plan, Observation obs) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(obs, "obs");

        // No-op observation (nothing identified) → return an equivalent, freshly-allocated plan.
        if (obs.step() == null) {
            return new Plan(new ArrayList<>(plan.steps()), plan.summary());
        }

        // The observed step has been acted on (succeeded, failed, or denied) — drop it from the
        // remaining plan and keep the rest in order. Never mutate the input plan.
        List<PlanStep> remaining = new ArrayList<>(plan.steps().size());
        for (PlanStep step : plan.steps()) {
            if (!step.id().equals(obs.step())) {
                remaining.add(step);
            }
        }
        return new Plan(List.copyOf(remaining), plan.summary());
    }
}
