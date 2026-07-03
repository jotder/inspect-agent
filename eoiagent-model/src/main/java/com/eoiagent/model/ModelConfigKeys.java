package com.eoiagent.model;

import com.eoiagent.core.ConfigKey;

/**
 * Configuration keys owned by the model module (conventions §11). ADR-0013 §1: when set, these
 * OVERRIDE the pack {@code ModelProfile} at platform assembly, so a deployment swaps models by
 * editing config (env/properties) — no recompilation. Null default = "not set, use the pack".
 */
public final class ModelConfigKeys {

    private ModelConfigKeys() {
    }

    /** Chat provider override: {@code ollama} or {@code openai-compatible}. */
    public static final ConfigKey<String> CHAT_PROVIDER =
            ConfigKey.of("eoiagent.model.chat.provider", String.class);

    /** Chat model id override (e.g. {@code qwen2.5:14b-instruct}, {@code ornith-1.0-9b}). */
    public static final ConfigKey<String> CHAT_MODEL_ID =
            ConfigKey.of("eoiagent.model.chat.modelId", String.class);

    /** Chat endpoint override (e.g. {@code http://localhost:11434}). */
    public static final ConfigKey<String> CHAT_BASE_URL =
            ConfigKey.of("eoiagent.model.chat.baseUrl", String.class);

    /** Embedding provider override: {@code onnx-all-minilm} or {@code ollama}. */
    public static final ConfigKey<String> EMBEDDING_PROVIDER =
            ConfigKey.of("eoiagent.model.embedding.provider", String.class);

    /** Embedding model id override. */
    public static final ConfigKey<String> EMBEDDING_MODEL_ID =
            ConfigKey.of("eoiagent.model.embedding.modelId", String.class);

    /** Embedding endpoint override. */
    public static final ConfigKey<String> EMBEDDING_BASE_URL =
            ConfigKey.of("eoiagent.model.embedding.baseUrl", String.class);
}
