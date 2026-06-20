package com.eoiagent.core;

/**
 * Thrown for an invalid/missing {@code eoiagent.profile}, a value that cannot be coerced to its
 * {@link ConfigKey#type()}, or a configuration that contradicts the active {@link DeploymentProfile}
 * (e.g. a hosted chat provider while {@code OFFLINE}). Raised at construction (fail fast) wherever
 * possible — see {@code docs/specs/config-profiles.md} §Error handling.
 */
public class ConfigException extends EoiAgentException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
