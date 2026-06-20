package com.eoiagent.config;

/**
 * A raw, untyped lookup over one configuration source, keyed by dotted {@code eoiagent.*} name.
 * Adapters build a {@code RawSource} once at construction (snapshotting env/properties/programmatic
 * values) so that {@link ConfigProvider#get} and {@link ConfigProvider#featureEnabled} perform no
 * I/O at call time (config-profiles AC7).
 */
@FunctionalInterface
public interface RawSource {

    /** Raw value for {@code dottedKey}, or {@code null} if this source has none. */
    String value(String dottedKey);
}
