package com.eoiagent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Composes several sources into one precedence chain: the first layer to provide a value wins, and
 * {@link ConfigKey#defaultValue()} applies only when no layer has it. The documented order is
 * programmatic &gt; env &gt; properties &gt; defaults (config-profiles AC10) — add layers
 * highest-precedence first.
 *
 * <p>Profile resolution and contradiction validation run once over the composed source, so a value
 * supplied by a lower layer is correctly overridden before any check.
 */
public final class LayeredConfigProvider extends AbstractConfigProvider {

    public LayeredConfigProvider(List<RawSource> layersHighestFirst) {
        super(compose(layersHighestFirst));
    }

    public static Builder builder() {
        return new Builder();
    }

    private static RawSource compose(List<RawSource> layersHighestFirst) {
        List<RawSource> layers = List.copyOf(Objects.requireNonNull(layersHighestFirst, "layers"));
        return key -> {
            for (RawSource layer : layers) {
                String value = layer.value(key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        };
    }

    /** Adds layers in descending precedence (first added wins ties). */
    public static final class Builder {
        private final List<RawSource> layers = new ArrayList<>();

        public Builder programmatic(Map<String, String> values) {
            layers.add(ProgrammaticConfigProvider.source(values));
            return this;
        }

        public Builder env() {
            return env(System.getenv());
        }

        public Builder env(Map<String, String> env) {
            layers.add(EnvConfigProvider.source(env));
            return this;
        }

        public Builder properties(Properties properties) {
            layers.add(PropertiesConfigProvider.source(properties));
            return this;
        }

        /** Escape hatch for a custom source; placed at the current (lowest-so-far) precedence. */
        public Builder source(RawSource source) {
            layers.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        public LayeredConfigProvider build() {
            return new LayeredConfigProvider(layers);
        }
    }
}
