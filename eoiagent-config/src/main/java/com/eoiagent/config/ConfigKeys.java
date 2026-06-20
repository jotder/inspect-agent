package com.eoiagent.config;

import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;

import java.util.List;

/**
 * The platform's owned config keys and the defaults registry. config-profiles owns
 * {@code eoiagent.profile} and the feature-enabling keys; per conventions §9 every other module's
 * key is registered here with a default so each owning spec maps to one.
 *
 * <p>Note: for the feature-enabling keys the authoritative answer is
 * {@link ConfigProvider#featureEnabled(Feature)}, which applies the profile-aware default and the
 * capability-matrix ceiling. The base default below applies to a direct {@code get(...)} call.
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    // --- Owned by this module --------------------------------------------------------------

    public static final ConfigKey<DeploymentProfile> PROFILE =
            new ConfigKey<>("eoiagent.profile", DeploymentProfile.class, DeploymentProfile.OFFLINE);

    public static final ConfigKey<Boolean> HOSTED_MODELS_ENABLED =
            new ConfigKey<>("eoiagent.features.hostedModels.enabled", Boolean.class, Boolean.FALSE);
    public static final ConfigKey<Boolean> MUTATING_ACTIONS_ENABLED =
            new ConfigKey<>("eoiagent.features.mutatingActions.enabled", Boolean.class, Boolean.TRUE);
    public static final ConfigKey<Boolean> MCP_TOOLS_ENABLED =
            new ConfigKey<>("eoiagent.features.mcpTools.enabled", Boolean.class, Boolean.FALSE);
    public static final ConfigKey<Boolean> PGVECTOR_ENABLED =
            new ConfigKey<>("eoiagent.features.pgvector.enabled", Boolean.class, Boolean.FALSE);
    public static final ConfigKey<Boolean> ADVANCED_RETRIEVAL_ENABLED =
            new ConfigKey<>("eoiagent.features.advancedRetrieval.enabled", Boolean.class, Boolean.FALSE);
    public static final ConfigKey<Boolean> LANGGRAPH_CHECKPOINTING_ENABLED =
            new ConfigKey<>("eoiagent.features.langgraphCheckpointing.enabled", Boolean.class, Boolean.FALSE);
    public static final ConfigKey<Boolean> LONG_TERM_MEMORY_ENABLED =
            new ConfigKey<>("eoiagent.features.longTermMemory.enabled", Boolean.class, Boolean.FALSE);

    /** MCP transport; OFFLINE permits {@code stdio} only (registry enforces "no remote MCP offline"). */
    public static final ConfigKey<String> MCP_TRANSPORT =
            new ConfigKey<>("eoiagent.tools.mcp.transport", String.class, "stdio");

    // --- Defaults for other modules' keys (owning spec maps to a default; see 03-deployment-profiles) -

    public static final ConfigKey<String> MODEL_CHAT_PROVIDER =
            ConfigKey.of("eoiagent.model.chat.provider", String.class);
    public static final ConfigKey<String> MODEL_CHAT_BASE_URL =
            ConfigKey.of("eoiagent.model.chat.baseUrl", String.class);
    public static final ConfigKey<String> MODEL_CHAT_MODEL_ID =
            ConfigKey.of("eoiagent.model.chat.modelId", String.class);
    public static final ConfigKey<String> MODEL_EMBEDDING_PROVIDER =
            new ConfigKey<>("eoiagent.model.embedding.provider", String.class, "onnx-all-minilm");
    public static final ConfigKey<String> VECTORSTORE_KIND =
            ConfigKey.of("eoiagent.vectorstore.kind", String.class);
    public static final ConfigKey<Boolean> APPROVAL_REQUIRED =
            new ConfigKey<>("eoiagent.approval.required", Boolean.class, Boolean.TRUE);
    public static final ConfigKey<String> AUDIT_SINK =
            ConfigKey.of("eoiagent.audit.sink", String.class);

    /** Known keys validated for coercion at provider construction (fail fast). */
    static List<ConfigKey<?>> all() {
        return List.of(
                PROFILE,
                HOSTED_MODELS_ENABLED, MUTATING_ACTIONS_ENABLED, MCP_TOOLS_ENABLED, PGVECTOR_ENABLED,
                ADVANCED_RETRIEVAL_ENABLED, LANGGRAPH_CHECKPOINTING_ENABLED, LONG_TERM_MEMORY_ENABLED,
                MCP_TRANSPORT,
                MODEL_CHAT_PROVIDER, MODEL_CHAT_BASE_URL, MODEL_CHAT_MODEL_ID, MODEL_EMBEDDING_PROVIDER,
                VECTORSTORE_KIND, APPROVAL_REQUIRED, AUDIT_SINK);
    }

    /** The boolean key that enables a feature (consulted only after the matrix permits it). */
    static ConfigKey<Boolean> enablingKey(Feature feature) {
        return switch (feature) {
            case HOSTED_MODELS -> HOSTED_MODELS_ENABLED;
            case MUTATING_ACTIONS -> MUTATING_ACTIONS_ENABLED;
            case MCP_TOOLS -> MCP_TOOLS_ENABLED;
            case PGVECTOR -> PGVECTOR_ENABLED;
            case ADVANCED_RETRIEVAL -> ADVANCED_RETRIEVAL_ENABLED;
            case LANGGRAPH_CHECKPOINTING -> LANGGRAPH_CHECKPOINTING_ENABLED;
            case LONG_TERM_MEMORY -> LONG_TERM_MEMORY_ENABLED;
        };
    }
}
