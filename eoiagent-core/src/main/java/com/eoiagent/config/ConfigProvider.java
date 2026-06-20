package com.eoiagent.config;

import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

/**
 * The single authority for the active {@link DeploymentProfile}, typed configuration values, and
 * the per-profile capability matrix. Every other module asks this port two things: "what is my
 * value for key K?" and "is feature F allowed in this deployment?". This is the mechanism that
 * makes the platform fail closed offline (constraint C2).
 *
 * <p>Component 11 in {@code docs/architecture/01-component-model.md}; full contract in
 * {@code docs/specs/config-profiles.md}.
 */
public interface ConfigProvider {

    /**
     * The active profile; never {@code null}. Resolved once at construction from
     * {@code eoiagent.profile} and fixed for the provider's lifetime. Thread-safe.
     */
    DeploymentProfile profile();

    /**
     * The typed value for {@code key}. Resolution order: explicit source (env/properties/
     * programmatic) then {@link ConfigKey#defaultValue()}. Returns {@code null} only when both
     * source and default are {@code null}. Coerces string sources to {@link ConfigKey#type()};
     * a malformed value throws {@code ConfigException}. Pure and thread-safe.
     */
    <T> T get(ConfigKey<T> key);

    /**
     * {@code true} only when the capability matrix for {@link #profile()} permits {@code feature}
     * <em>and</em> its enabling config key is on. A profile marked ✗ for a feature returns
     * {@code false} regardless of config — config can restrict further, never loosen. Never throws;
     * never performs I/O. Thread-safe. This is the gate consulted before touching the network or a
     * mutating path.
     */
    boolean featureEnabled(Feature feature);
}
