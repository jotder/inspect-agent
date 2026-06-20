package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.safety.PolicyEngine;

/** Permissive {@link PolicyEngine} for wiring the registry in runtime tests (RBAC tested in T-110). */
final class AllowAllPolicyEngine implements PolicyEngine {

    @Override
    public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
        return true;
    }

    @Override
    public void check(AgentContext ctx, ToolSpec tool) {
        // allow all
    }
}
