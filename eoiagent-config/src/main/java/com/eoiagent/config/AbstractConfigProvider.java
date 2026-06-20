package com.eoiagent.config;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Shared {@link ConfigProvider} logic over a single {@link RawSource}: profile resolution, typed
 * coercion, the capability-matrix gate, and fail-fast validation. Concrete adapters only supply the
 * source (env / properties / programmatic / layered).
 *
 * <p>The provider is immutable after construction; {@link #get} and {@link #featureEnabled} read the
 * snapshotted source only and never perform I/O.
 */
public abstract class AbstractConfigProvider implements ConfigProvider {

    /** Chat providers that imply internet egress; forbidden where {@code HOSTED_MODELS} is ✗. */
    private static final Set<String> HOSTED_PROVIDERS = Set.of("anthropic", "gemini", "openai");

    private final RawSource source;
    private final DeploymentProfile profile;

    protected AbstractConfigProvider(RawSource source) {
        this.source = Objects.requireNonNull(source, "source");
        this.profile = resolveProfile(source);
        validate();
    }

    @Override
    public DeploymentProfile profile() {
        return profile;
    }

    @Override
    public <T> T get(ConfigKey<T> key) {
        Objects.requireNonNull(key, "key");
        String raw = source.value(key.name());
        if (raw == null) {
            return key.defaultValue();
        }
        return Coercion.coerce(raw, key.type(), key.name());
    }

    @Override
    public boolean featureEnabled(Feature feature) {
        Objects.requireNonNull(feature, "feature");
        // 1) hard gate: a ✗ cell is unreachable regardless of config.
        if (!CapabilityMatrix.permits(profile, feature)) {
            return false;
        }
        // 2) consult the enabling key; absent → the profile-aware default.
        String raw = source.value(ConfigKeys.enablingKey(feature).name());
        if (raw != null) {
            return Coercion.coerce(raw, Boolean.class, ConfigKeys.enablingKey(feature).name());
        }
        return defaultEnabled(profile, feature);
    }

    // --- construction-time resolution & validation (no calls to overridable methods) ----------

    private static DeploymentProfile resolveProfile(RawSource source) {
        String raw = source.value(ConfigKeys.PROFILE.name());
        if (raw == null || raw.isBlank()) {
            return DeploymentProfile.OFFLINE; // fail-closed default (AC3)
        }
        return Coercion.coerce(raw, DeploymentProfile.class, ConfigKeys.PROFILE.name());
    }

    private void validate() {
        // Coercion fail-fast for every known key present in the source (AC4).
        for (ConfigKey<?> key : ConfigKeys.all()) {
            String raw = source.value(key.name());
            if (raw != null) {
                Coercion.coerce(raw, key.type(), key.name());
            }
        }
        validateNoContradiction();
    }

    /** Reject config that contradicts the profile (AC8, and OFFLINE MCP transport for AC9). */
    private void validateNoContradiction() {
        if (!CapabilityMatrix.permits(profile, Feature.HOSTED_MODELS)) {
            String chat = source.value(ConfigKeys.MODEL_CHAT_PROVIDER.name());
            if (chat != null && HOSTED_PROVIDERS.contains(chat.trim().toLowerCase(Locale.ROOT))) {
                throw new ConfigException("profile " + profile
                        + " forbids hosted models, but eoiagent.model.chat.provider='" + chat + "'");
            }
        }
        if (profile == DeploymentProfile.OFFLINE) {
            String transport = source.value(ConfigKeys.MCP_TRANSPORT.name());
            if (transport != null && !transport.trim().equalsIgnoreCase("stdio")) {
                throw new ConfigException("OFFLINE permits MCP 'stdio' only, but "
                        + "eoiagent.tools.mcp.transport='" + transport + "'");
            }
        }
    }

    /** Profile-aware default for an enabling key that the source does not set. */
    private static boolean defaultEnabled(DeploymentProfile profile, Feature feature) {
        return switch (feature) {
            case HOSTED_MODELS -> profile == DeploymentProfile.CLOUD;
            case MUTATING_ACTIONS -> true;                              // always on, still approval-gated
            case MCP_TOOLS, PGVECTOR -> profile != DeploymentProfile.OFFLINE;
            case ADVANCED_RETRIEVAL,                                    // Phase 2
                 LANGGRAPH_CHECKPOINTING,                               // Phase 3
                 LONG_TERM_MEMORY -> false;                            // Phase 3
        };
    }
}
