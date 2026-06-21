package com.eoiagent.app;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import java.util.Set;

/**
 * Maps product roles onto the platform's {@link Role} and grants capabilities per role. {@code
 * mapRole(hostRole)} is total (never null; default to the least-privileged {@code Role.USER}); the
 * core {@code PolicyEngine} enforces {@code grants(role)} as the allowed subset.
 */
public interface PolicyProfile {

    /** Translates a product/host role string into a platform {@link Role}. */
    Role mapRole(String hostRole);

    /** Capabilities allowed for the given role. */
    Set<Capability> grants(Role role);
}
