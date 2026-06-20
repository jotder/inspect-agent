package com.eoiagent.safety;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;

/** Port deciding whether a role/profile may exercise a capability or tool. */
public interface PolicyEngine {

    boolean allows(Role role, Capability cap, DeploymentProfile profile);

    void check(AgentContext ctx, ToolSpec tool);
}
