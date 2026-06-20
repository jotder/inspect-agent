package com.eoiagent.core;

import java.util.Objects;

/**
 * A typed configuration key. Modules declare their keys as {@code public static final ConfigKey<?>}
 * constants; a {@code ConfigProvider} reads them, coercing raw string sources to {@link #type()}
 * and falling back to {@link #defaultValue()} when a source has no value.
 *
 * @param name         dotted key, {@code eoiagent.} prefix (e.g. {@code eoiagent.model.chat.provider})
 * @param type         the value type ({@code String}/{@code Boolean}/{@code Integer}/{@code Long}/enum)
 * @param defaultValue value when no source provides one; may be {@code null} (documented per key)
 * @param <T>          the resolved value type
 */
public record ConfigKey<T>(String name, Class<T> type, T defaultValue) {

    public ConfigKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("ConfigKey name must not be blank");
        }
    }

    /** Convenience factory for a key whose default is {@code null}. */
    public static <T> ConfigKey<T> of(String name, Class<T> type) {
        return new ConfigKey<>(name, type, null);
    }
}
