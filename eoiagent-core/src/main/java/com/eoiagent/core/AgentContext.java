package com.eoiagent.core;

import java.util.Map;

/** Per-invocation context carrying identity, role, deployment profile and page state. */
public record AgentContext(AppId app,
                           SessionId session,
                           UserId user,
                           Role role,
                           DeploymentProfile profile,
                           PageContext page,
                           Map<String, String> attributes) {
}
