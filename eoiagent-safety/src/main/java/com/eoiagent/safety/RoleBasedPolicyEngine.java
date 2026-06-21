package com.eoiagent.safety;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolSpec;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The default {@link PolicyEngine}: an immutable {@code Role × Capability} grant table (the
 * recommended table from the approval-governance spec), intersected with the deployment-profile
 * capability matrix. Pure-Java, deterministic, side-effect-free — safe to call for UI filtering.
 *
 * <p>For the current capability set the profile matrix permits every {@link Capability} in every
 * profile (mutating capabilities map to {@code MUTATING_ACTIONS}, which is allowed — though gated —
 * in OFFLINE/ON_PREM_HOSTED/CLOUD; see {@code docs/architecture/03-deployment-profiles.md}). So
 * {@link #allows} reduces to grant-table membership and is profile-independent today. The
 * <em>config-gated</em> enablement of {@code MUTATING_ACTIONS} (the "turn it on" half) is enforced
 * separately at {@code ToolRegistry.dispatch} via {@code ConfigProvider.featureEnabled}, never here.
 *
 * <p>A client may further restrict these grants (never loosen) by supplying a stricter engine; this
 * adapter ships the ceiling.
 */
public final class RoleBasedPolicyEngine implements PolicyEngine {

    private static final Map<Role, Set<Capability>> GRANTS = buildGrants();

    private static Map<Role, Set<Capability>> buildGrants() {
        EnumMap<Role, Set<Capability>> grants = new EnumMap<>(Role.class);
        grants.put(Role.ADMIN, EnumSet.allOf(Capability.class));
        grants.put(Role.SUPPORT, EnumSet.of(
                Capability.READ_DOCS, Capability.READ_METADATA, Capability.READ_SCHEMA,
                Capability.RUN_SQL_READONLY, Capability.GENERATE_SQL, Capability.INVESTIGATE,
                Capability.TRIGGER_JOB));
        grants.put(Role.ANALYST, EnumSet.of(
                Capability.READ_DOCS, Capability.READ_METADATA, Capability.READ_SCHEMA,
                Capability.RUN_SQL_READONLY, Capability.GENERATE_SQL));
        grants.put(Role.USER, EnumSet.of(
                Capability.READ_DOCS, Capability.READ_METADATA));
        return grants;
    }

    @Override
    public boolean allows(Role role, Capability cap, DeploymentProfile profile) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(cap, "cap");
        Objects.requireNonNull(profile, "profile");
        // Grant-table membership; the profile matrix permits all current capabilities in all profiles.
        return GRANTS.getOrDefault(role, Set.of()).contains(cap);
    }

    @Override
    public void check(AgentContext ctx, ToolSpec tool) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(tool, "tool");
        // Higher privilege = lower Role ordinal (ADMIN, SUPPORT, ANALYST, USER).
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
