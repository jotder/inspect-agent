package com.eoiagent.app;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import java.util.Map;

/**
 * The pack's deployment profile, feature overrides and shipped config defaults. {@code profile()}
 * is non-null; {@code featureOverrides()} may only <em>restrict</em> within the profile's capability
 * matrix (never enable a feature the profile forbids — validated); {@code configDefaults()} are
 * {@code eoiagent.*} keys a host may override.
 */
public interface PackConfig {

    /** The deployment profile this pack targets. */
    DeploymentProfile profile();

    /** Feature toggles within what the profile matrix permits. */
    Map<Feature, Boolean> featureOverrides();

    /** {@code eoiagent.*} configuration defaults this pack ships. */
    Map<String, String> configDefaults();
}
