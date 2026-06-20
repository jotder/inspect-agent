package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.safety.PolicyEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The enforced {@link ToolRegistry}: the single choke point through which the orchestrator sees and
 * calls tools. {@link #visibleTo} filters registered tools by role rank, the {@link PolicyEngine}'s
 * capability/profile decision, and the read-only flag; {@link #dispatch} re-checks policy, validates
 * arguments, invokes read-only tools, and records a {@code TOOL_CALL} {@link AuditEvent}.
 *
 * <p>Phase 1 is <strong>read-only</strong>: mutating tools are never visible, and dispatching one
 * fails closed with {@link PolicyViolation} — the {@code ApprovalGate} + dry-run path (and thus
 * {@code APPROVAL}/{@code MUTATION} audit events) arrives in Phase 2.
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();
    private final PolicyEngine policy;
    private final AuditSink audit;

    public DefaultToolRegistry(PolicyEngine policy, AuditSink audit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.spec().name();
        if (byName.containsKey(name)) {
            throw new ConfigException("duplicate tool name: " + name);
        }
        byName.put(name, tool);
    }

    @Override
    public List<ToolSpec> visibleTo(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        List<ToolSpec> visible = new ArrayList<>();
        for (Tool tool : byName.values()) {
            ToolSpec spec = tool.spec();
            if (spec.mutating()) {
                continue; // Phase 1: mutating tools are not exposed (no approval gate yet)
            }
            if (!roleSatisfies(ctx.role(), spec.requiredRole())) {
                continue;
            }
            if (!policy.allows(ctx.role(), spec.capability(), ctx.profile())) {
                continue;
            }
            visible.add(spec);
        }
        return visible;
    }

    @Override
    public ToolResult dispatch(ToolCall call, AgentContext ctx) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(ctx, "ctx");
        Tool tool = byName.get(call.toolName());
        if (tool == null) {
            return new ToolResult(false, null, "unknown tool: " + call.toolName(), Map.of());
        }
        ToolSpec spec = tool.spec();

        policy.check(ctx, spec); // throws PolicyViolation on role/capability/profile denial

        if (spec.mutating()) {
            // Fail closed: invariant 2 (no mutation without a prior APPROVED) cannot hold without a gate.
            throw new PolicyViolation("mutating tool '" + spec.name()
                    + "' requires approval, which is not available in Phase 1");
        }

        String invalid = ArgumentValidator.validate(spec.jsonSchema(), call.arguments());
        if (invalid != null) {
            return new ToolResult(false, null, "invalid arguments: " + invalid, Map.of());
        }

        ToolResult result;
        try {
            result = tool.invoke(call);
        } catch (RuntimeException e) {
            audit.record(event(ctx, call, AuditKind.ERROR, "tool failed: " + spec.name()));
            throw e;
        }
        audit.record(event(ctx, call, AuditKind.TOOL_CALL, "tool: " + spec.name()));
        return result;
    }

    /** Higher privilege = lower {@link Role} ordinal (ADMIN, SUPPORT, ANALYST, USER). */
    private static boolean roleSatisfies(Role actual, Role required) {
        return actual.ordinal() <= required.ordinal();
    }

    private static AuditEvent event(AgentContext ctx, ToolCall call, AuditKind kind, String summary) {
        Map<String, Object> details = Map.of("tool", call.toolName());
        return new AuditEvent(Instant.now(), ctx.app(), call.run(), ctx.session(), ctx.user(),
                kind, summary, details);
    }
}
