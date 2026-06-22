package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;
import com.eoiagent.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A read-only {@link ToolRegistry} view that narrows a delegate registry to a single worker's
 * {@link Capability} subset (Flow D, spec §4.2 — "narrowed tool subset"). {@link #visibleTo} returns
 * only the delegate's visible specs whose capability is in the worker's set, and {@link #dispatch}
 * fails closed with {@link PolicyViolation} for any tool outside that subset before reaching the
 * delegate. The worker boundary is enforced here, in our adapter — not assumed from a library.
 */
final class WorkerToolView implements ToolRegistry {

    private final ToolRegistry delegate;
    private final Set<Capability> allowed;

    WorkerToolView(ToolRegistry delegate, Set<Capability> allowed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.allowed = Set.copyOf(Objects.requireNonNull(allowed, "allowed"));
    }

    @Override
    public void register(Tool tool) {
        throw new UnsupportedOperationException("worker tool views are read-only; register on the base registry");
    }

    @Override
    public List<ToolSpec> visibleTo(AgentContext ctx) {
        return delegate.visibleTo(ctx).stream()
                .filter(spec -> allowed.contains(spec.capability()))
                .toList();
    }

    @Override
    public ToolResult dispatch(ToolCall call, AgentContext ctx) {
        boolean withinSubset = delegate.visibleTo(ctx).stream()
                .anyMatch(spec -> spec.name().equals(call.toolName()) && allowed.contains(spec.capability()));
        if (!withinSubset) {
            throw new PolicyViolation("tool '" + call.toolName()
                    + "' is outside this worker's capability subset " + allowed);
        }
        return delegate.dispatch(call, ctx);
    }
}
