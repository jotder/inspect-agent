package com.eoiagent.config;

import com.eoiagent.core.ConfigKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration set in code — the test/embed default. Values are dotted-key strings; a {@link Builder}
 * offers typed {@code set(ConfigKey, value)} puts. Snapshotted at construction.
 */
public final class ProgrammaticConfigProvider extends AbstractConfigProvider {

    public ProgrammaticConfigProvider(Map<String, String> values) {
        super(source(values));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A {@link RawSource} over a defensive copy of dotted-key values. */
    static RawSource source(Map<String, String> values) {
        Map<String, String> snapshot = Map.copyOf(Objects.requireNonNull(values, "values"));
        return snapshot::get;
    }

    /** Fluent, type-checked builder. */
    public static final class Builder {
        private final Map<String, String> values = new HashMap<>();

        public <T> Builder set(ConfigKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            values.put(key.name(), String.valueOf(value));
            return this;
        }

        public Builder set(String dottedKey, String value) {
            values.put(Objects.requireNonNull(dottedKey, "dottedKey"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        public ProgrammaticConfigProvider build() {
            return new ProgrammaticConfigProvider(values);
        }
    }
}
