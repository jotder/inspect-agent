package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.safety.PolicyEngine;

import java.util.Set;

/**
 * In-test {@link PolicyEngine}: denies a configurable set of capabilities for all roles/profiles, and
 * enforces role rank (lower {@link Role} ordinal = higher privilege) in {@link #check}.
 */
final class FakePolicyEngine implements PolicyEngine {

    private final Set<Capability> denied;

    FakePolicyEngine(Capability... deniedCaps) {
        this.denied = Set.of(deniedCaps);
    }

    @Override
    public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
        return !denied.contains(cap);
    }

    @Override
    public void check(AgentContext ctx, ToolSpec tool) {
        if (ctx.role().ordinal() > tool.requiredRole().ordinal()) {
            throw new PolicyViolation("role " + ctx.role() + " is below required " + tool.requiredRole());
        }
        if (!allows(ctx.role(), tool.capability(), ctx.profile())) {
            throw new PolicyViolation("capability denied: " + tool.capability());
        }
    }
}
