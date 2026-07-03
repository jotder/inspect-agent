package com.eoiagent.platform;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.safety.PolicyEngine;
import com.eoiagent.safety.RoleBasedPolicyEngine;

import java.util.Objects;

/**
 * T-354 policy reconciliation: the pack's {@code PolicyProfile} is a <em>restriction overlay</em>
 * on the platform's default Role×Capability grant table ({@link RoleBasedPolicyEngine}) — a pack
 * can narrow what a role may do, never widen it past the default ceiling. Both engines must allow;
 * {@code check} is fail-closed through both (ceiling first, so an over-granting pack profile can
 * never smuggle a capability through).
 */
final class CeilingPolicyEngine implements PolicyEngine {

    private final PolicyEngine ceiling = new RoleBasedPolicyEngine();
    private final PolicyEngine packProfile;

    CeilingPolicyEngine(PolicyEngine packProfile) {
        this.packProfile = Objects.requireNonNull(packProfile, "packProfile");
    }

    @Override
    public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
        return ceiling.allows(role, cap, profile) && packProfile.allows(role, cap, profile);
    }

    @Override
    public void check(AgentContext ctx, ToolSpec tool) {
        ceiling.check(ctx, tool);
        packProfile.check(ctx, tool);
    }
}
