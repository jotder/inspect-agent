package com.eoiagent.examples;

import com.eoiagent.app.PolicyProfile;
import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.Role;

import java.util.List;

/**
 * Showcases RBAC and deployment posture: how the pack maps host role strings onto the platform
 * {@link Role}, what capabilities each role is granted, and the OFFLINE config the pack ships.
 */
public final class PolicyAndProfilesDemo {

    private PolicyAndProfilesDemo() {
    }

    public static void main(String[] args) {
        ReferenceApplicationPack pack = new ReferenceApplicationPack();
        PolicyProfile policy = pack.policyProfile();

        DemoSupport.header("Host role -> platform Role mapping");
        for (String hostRole : List.of("viewer", "engineer", "admin", "unknown-role")) {
            DemoSupport.kv(hostRole, policy.mapRole(hostRole));
        }

        DemoSupport.header("Capability grants per Role");
        for (Role role : Role.values()) {
            DemoSupport.kv(role.name(), policy.grants(role));
        }

        DemoSupport.header("Deployment config the pack ships");
        DemoSupport.kv("profile", pack.config().profile());
        DemoSupport.kv("featureOverrides", pack.config().featureOverrides());
        pack.config().configDefaults().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> DemoSupport.bullet(e.getKey() + " = " + e.getValue()));
    }
}
