package com.eoiagent.config;

import com.eoiagent.core.ConfigException;

import java.util.Locale;

/**
 * Coerces a raw string source value to a {@link com.eoiagent.core.ConfigKey} type. Supports
 * {@code String}, {@code Boolean}, {@code Integer}, {@code Long}, and any {@code enum}. A value
 * that cannot be coerced throws {@link ConfigException} (surfaced at construction for known keys).
 */
final class Coercion {

    private Coercion() {
    }

    @SuppressWarnings("unchecked")
    static <T> T coerce(String raw, Class<T> type, String keyName) {
        if (type == String.class) {
            return (T) raw;
        }
        String v = raw.trim();
        if (type == Boolean.class) {
            if (v.equalsIgnoreCase("true")) {
                return (T) Boolean.TRUE;
            }
            if (v.equalsIgnoreCase("false")) {
                return (T) Boolean.FALSE;
            }
            throw new ConfigException(
                    "config key '" + keyName + "': not a boolean: '" + raw + "'");
        }
        if (type == Integer.class) {
            try {
                return (T) Integer.valueOf(v);
            } catch (NumberFormatException e) {
                throw new ConfigException(
                        "config key '" + keyName + "': not an integer: '" + raw + "'", e);
            }
        }
        if (type == Long.class) {
            try {
                return (T) Long.valueOf(v);
            } catch (NumberFormatException e) {
                throw new ConfigException(
                        "config key '" + keyName + "': not a long: '" + raw + "'", e);
            }
        }
        if (type.isEnum()) {
            return (T) coerceEnum(v, type.asSubclass(Enum.class), keyName);
        }
        throw new ConfigException(
                "config key '" + keyName + "': unsupported type " + type.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Enum coerceEnum(String v, Class<? extends Enum> type, String keyName) {
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException exact) {
            try {
                return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new ConfigException(
                        "config key '" + keyName + "': '" + v + "' is not a valid "
                                + type.getSimpleName());
            }
        }
    }
}
