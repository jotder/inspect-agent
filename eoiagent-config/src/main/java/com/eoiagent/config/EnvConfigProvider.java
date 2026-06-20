package com.eoiagent.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads configuration from environment variables. A dotted key maps to an env var by upper-casing
 * and replacing {@code .} with {@code _} — e.g. {@code eoiagent.model.chat.provider} →
 * {@code EOIAGENT_MODEL_CHAT_PROVIDER} (config-profiles AC6).
 *
 * <p>The environment is snapshotted at construction, so lookups perform no I/O.
 */
public final class EnvConfigProvider extends AbstractConfigProvider {

    /** Reads the process environment ({@link System#getenv()}). */
    public EnvConfigProvider() {
        this(System.getenv());
    }

    /** Reads the supplied environment map (test seam). */
    public EnvConfigProvider(Map<String, String> env) {
        super(source(env));
    }

    static String toEnvVar(String dottedKey) {
        return dottedKey.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /** A {@link RawSource} over an env map, applying the dotted→ENV name mapping. */
    static RawSource source(Map<String, String> env) {
        Map<String, String> snapshot = Map.copyOf(Objects.requireNonNull(env, "env"));
        return dottedKey -> snapshot.get(toEnvVar(dottedKey));
    }
}
