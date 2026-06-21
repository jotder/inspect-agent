package com.eoiagent.tool;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.safety.ApprovalGate;
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
 * capability/profile decision, and the read-only/feature flags; {@link #dispatch} re-checks policy,
 * validates arguments, invokes read-only tools, and — for mutating tools — routes through the
 * {@link ApprovalGate} (dry-run → request) so that <strong>no mutating {@code ToolResult} is ever
 * produced without a preceding {@code APPROVED} audit event</strong> (C4 / invariant 2).
 *
 * <p>Two construction modes. The two-arg constructor is the Phase-1 <strong>read-only</strong>
 * registry: with no {@link ApprovalGate}/{@link ConfigProvider} the {@code MUTATING_ACTIONS} feature
 * can never be on, so mutating tools stay hidden and dispatching one fails closed with
 * {@link PolicyViolation}. The four-arg constructor enables the mutating path: mutating tools become
 * visible and dispatchable only when {@code ConfigProvider.featureEnabled(MUTATING_ACTIONS)} and the
 * gate {@code APPROVED}s the call.
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();
    private final PolicyEngine policy;
    private final ApprovalGate approvalGate; // null → read-only registry (no mutating path)
    private final ConfigProvider config;     // null → read-only registry
    private final AuditSink audit;

    /** Phase-1 read-only registry: no approval gate, mutating tools fail closed. */
    public DefaultToolRegistry(PolicyEngine policy, AuditSink audit) {
        this(policy, null, null, audit);
    }

    /** Mutating-capable registry: routes mutating tools through {@code approvalGate} when enabled. */
    public DefaultToolRegistry(PolicyEngine policy, ApprovalGate approvalGate,
                               ConfigProvider config, AuditSink audit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.approvalGate = approvalGate;
        this.config = config;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** True only when this registry is wired for mutation AND the active profile/config enables it. */
    private boolean mutatingEnabled() {
        return approvalGate != null && config != null && config.featureEnabled(Feature.MUTATING_ACTIONS);
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
        boolean mutatingEnabled = mutatingEnabled();
        for (Tool tool : byName.values()) {
            ToolSpec spec = tool.spec();
            if (spec.mutating() && !mutatingEnabled) {
                continue; // mutating tools are hidden unless MUTATING_ACTIONS is enabled for this profile
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

        if (spec.mutating() && !mutatingEnabled()) {
            // Fail closed: invariant 2 (no mutation without a prior APPROVED) cannot hold without an
            // enabled gate. Thrown before any approval or invoke (offline/feature-off fail-closed).
            throw new PolicyViolation("mutating tool '" + spec.name()
                    + "' requires the MUTATING_ACTIONS feature, which is not enabled");
        }

        String invalid = ArgumentValidator.validate(spec.jsonSchema(), call.arguments());
        if (invalid != null) {
            return new ToolResult(false, null, "invalid arguments: " + invalid, Map.of());
        }

        if (spec.mutating()) {
            return dispatchMutating(tool, spec, call, ctx);
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

    /**
     * Flow C step 3: dry-run, request human approval, audit the decision, and invoke only on
     * {@code APPROVED}. Emits {@code APPROVAL} then (only if approved) {@code MUTATION} — never a
     * {@code MUTATION} without a preceding {@code APPROVED} (invariant 2).
     */
    private ToolResult dispatchMutating(Tool tool, ToolSpec spec, ToolCall call, AgentContext ctx) {
        DryRunResult preview = approvalGate.dryRun(call);
        ApprovalRequest req = new ApprovalRequest(call.run(), call,
                "Approve mutating action '" + spec.name() + "'", preview);
        ApprovalDecision decision = approvalGate.request(req); // blocks for the human

        audit.record(event(ctx, call, AuditKind.APPROVAL, "approval " + decision + ": " + spec.name()));
        if (decision != ApprovalDecision.APPROVED) {
            return new ToolResult(false, null, "approval " + decision, Map.of());
        }

        ToolResult result;
        try {
            result = tool.invoke(call);
        } catch (RuntimeException e) {
            audit.record(event(ctx, call, AuditKind.ERROR, "tool failed: " + spec.name()));
            throw e;
        }
        audit.record(event(ctx, call, AuditKind.MUTATION, "mutation: " + spec.name()));
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
