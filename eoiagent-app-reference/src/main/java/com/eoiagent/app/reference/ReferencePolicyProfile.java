package com.eoiagent.app.reference;

import com.eoiagent.app.PolicyProfile;
import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maps Acme host roles onto the platform {@link Role} and grants capabilities per tier. {@code
 * mapRole} is total (unknown → least-privileged {@code USER}); {@code grants} returns read-only
 * capabilities for the non-admin tiers (mutating capabilities only appear for ADMIN and remain gated
 * by the deployment profile regardless).
 */
final class ReferencePolicyProfile implements PolicyProfile {

    @Override
    public Role mapRole(String hostRole) {
        return switch (hostRole == null ? "" : hostRole.toLowerCase(Locale.ROOT)) {
            case "admin" -> Role.ADMIN;
            case "engineer" -> Role.ANALYST; // Acme "engineer" → ANALYST tier
            case "viewer" -> Role.USER;
            default -> Role.USER; // total → least-privileged
        };
    }

    @Override
    public Set<Capability> grants(Role role) {
        return switch (role) {
            case USER -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA);
            case ANALYST -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA,
                    Capability.READ_SCHEMA, Capability.RUN_SQL_READONLY);
            case SUPPORT -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA, Capability.INVESTIGATE);
            case ADMIN -> EnumSet.allOf(Capability.class); // full set; mutating still gated by profile
        };
    }
}
