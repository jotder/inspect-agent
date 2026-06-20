package com.eoiagent.host;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserId;
import java.util.Map;

/** A request from the host to open a session for a user. */
public record SessionRequest(UserId user,
                             Role role,
                             DeploymentProfile profile,
                             PageContext initialPage,
                             Map<String, String> attributes) {
}
