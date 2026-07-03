package com.eoiagent.runtime;

import com.eoiagent.core.ConfigKey;

/**
 * Configuration keys owned by the runtime module (conventions §11: a module owns its
 * {@link ConfigKey} constants and reads them through the {@code ConfigProvider} port). Defaults
 * mirror the OFFLINE column of the orchestration-runtime spec.
 */
public final class RuntimeConfigKeys {

    private RuntimeConfigKeys() {
    }

    /** Hard upper bound on ReAct iterations; the loop concludes gracefully when reached. */
    public static final ConfigKey<Integer> MAX_STEPS =
            new ConfigKey<>("eoiagent.runtime.maxSteps", Integer.class, 12);

    /** Tool results larger than this (serialized bytes) are offloaded to the {@code Scratchpad}. */
    public static final ConfigKey<Integer> OFFLOAD_THRESHOLD_BYTES =
            new ConfigKey<>("eoiagent.runtime.offloadThresholdBytes", Integer.class, 8192);

    /**
     * Most recent conversation turns replayed to the model from session memory (T-351). The
     * stored transcript keeps everything; this bounds only the context sent per call.
     */
    public static final ConfigKey<Integer> MEMORY_MAX_MESSAGES =
            new ConfigKey<>("eoiagent.runtime.memory.maxMessages", Integer.class, 20);

    /** Top-k chunks retrieved per QA turn when a retriever is wired into the loop (T-352). */
    public static final ConfigKey<Integer> RAG_TOP_K =
            new ConfigKey<>("eoiagent.runtime.rag.topK", Integer.class, 4);

    /** Hard upper bound on sub-agent delegations the supervisor may make per run (Flow D). */
    public static final ConfigKey<Integer> SUPERVISOR_MAX_WORKERS =
            new ConfigKey<>("eoiagent.runtime.supervisor.maxWorkers", Integer.class, 3);

    /** Phase 3 (Flow E): save a {@code Checkpoint} after each graph node so the run survives restart. */
    public static final ConfigKey<Boolean> CHECKPOINT_EVERY_NODE =
            new ConfigKey<>("eoiagent.runtime.checkpoint.everyNode", Boolean.class, true);
}
