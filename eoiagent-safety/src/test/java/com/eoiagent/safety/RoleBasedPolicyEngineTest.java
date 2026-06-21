package com.eoiagent.safety;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RoleBasedPolicyEngine: the default grant table (AC1) and check() enforcement (AC2) for T-203. */
class RoleBasedPolicyEngineTest {

    private final RoleBasedPolicyEngine policy = new RoleBasedPolicyEngine();

    private static AgentContext ctx(Role role) {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                role, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ToolSpec tool(Role requiredRole, Capability cap, boolean mutating) {
        return new ToolSpec("t", "desc", "{}", mutating, requiredRole, cap);
    }

    @Test
    void grantTableMatchesSpec() { // AC1
        // USER: only READ_DOCS + READ_METADATA
        assertThat(policy.allows(Role.USER, Capability.READ_DOCS, DeploymentProfile.OFFLINE)).isTrue();
        assertThat(policy.allows(Role.USER, Capability.READ_METADATA, DeploymentProfile.CLOUD)).isTrue();
        assertThat(policy.allows(Role.USER, Capability.READ_SCHEMA, DeploymentProfile.OFFLINE)).isFalse();
        assertThat(policy.allows(Role.USER, Capability.RUN_PIPELINE, DeploymentProfile.CLOUD)).isFalse();

        // ANALYST: read + SQL, but not INVESTIGATE or any mutating
        assertThat(policy.allows(Role.ANALYST, Capability.GENERATE_SQL, DeploymentProfile.OFFLINE)).isTrue();
        assertThat(policy.allows(Role.ANALYST, Capability.INVESTIGATE, DeploymentProfile.OFFLINE)).isFalse();
        assertThat(policy.allows(Role.ANALYST, Capability.RUN_PIPELINE, DeploymentProfile.OFFLINE)).isFalse();

        // SUPPORT: + INVESTIGATE + TRIGGER_JOB, but not RUN_PIPELINE/EDIT_CONFIG
        assertThat(policy.allows(Role.SUPPORT, Capability.INVESTIGATE, DeploymentProfile.OFFLINE)).isTrue();
        assertThat(policy.allows(Role.SUPPORT, Capability.TRIGGER_JOB, DeploymentProfile.OFFLINE)).isTrue();
        assertThat(policy.allows(Role.SUPPORT, Capability.RUN_PIPELINE, DeploymentProfile.OFFLINE)).isFalse();
        assertThat(policy.allows(Role.SUPPORT, Capability.EDIT_CONFIG, DeploymentProfile.OFFLINE)).isFalse();

        // ADMIN: everything, in every profile (MUTATING_ACTIONS allowed in all profiles)
        for (Capability cap : Capability.values()) {
            assertThat(policy.allows(Role.ADMIN, cap, DeploymentProfile.OFFLINE))
                    .as("ADMIN should be granted %s", cap).isTrue();
        }
        assertThat(policy.allows(Role.ADMIN, Capability.RUN_PIPELINE, DeploymentProfile.OFFLINE)).isTrue();
    }

    @Test
    void checkThrowsWhenRequiredRoleOutranksCaller() { // AC2(a)
        assertThatThrownBy(() -> policy.check(ctx(Role.USER), tool(Role.ADMIN, Capability.READ_DOCS, false)))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("below the required role");
    }

    @Test
    void checkThrowsWhenCapabilityNotGranted() { // AC2(b)
        // USER meets requiredRole USER, but RUN_PIPELINE is not granted to USER.
        assertThatThrownBy(() -> policy.check(ctx(Role.USER), tool(Role.USER, Capability.RUN_PIPELINE, true)))
                .isInstanceOf(PolicyViolation.class)
                .hasMessageContaining("not granted capability");
    }

    @Test
    void checkReturnsNormallyWhenPermitted() { // AC2 (allow path)
        assertThatCode(() -> policy.check(ctx(Role.ADMIN), tool(Role.ADMIN, Capability.RUN_PIPELINE, true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.check(ctx(Role.USER), tool(Role.USER, Capability.READ_DOCS, false)))
                .doesNotThrowAnyException();
    }
}
