package com.eoiagent.platform;

import com.eoiagent.app.PolicyProfile;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.safety.PolicyEngine;

import java.util.Objects;

/**
 * The {@link PolicyEngine} the platform builds from a pack's {@link PolicyProfile}: a role is allowed
 * a capability iff {@code policyProfile.grants(role)} contains it. {@link #check} additionally enforces
 * the tool's {@code requiredRole} by rank (higher privilege = lower {@link Role} ordinal — the same
 * convention the {@code ToolRegistry} uses), failing closed with {@link PolicyViolation}.
 */
final class ProfilePolicyEngine implements PolicyEngine {

    private final PolicyProfile policyProfile;

    ProfilePolicyEngine(PolicyProfile policyProfile) {
        this.policyProfile = Objects.requireNonNull(policyProfile, "policyProfile");
    }

    @Override
    public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cap, "cap");
        return policyProfile.grants(role).contains(cap);
    }

    @Override
    public void check(AgentContext ctx, ToolSpec tool) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tool, "tool");
        if (ctx.role().ordinal() > tool.requiredRole().ordinal()) {
            throw new PolicyViolation("role " + ctx.role() + " is below the required role "
                    + tool.requiredRole() + " for tool '" + tool.name() + "'");
        }
        if (!allows(ctx.role(), tool.capability(), ctx.profile())) {
            throw new PolicyViolation("role " + ctx.role() + " is not granted capability "
                    + tool.capability() + " required by tool '" + tool.name() + "'");
        }
    }
}
