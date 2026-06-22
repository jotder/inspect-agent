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

    /** Hard upper bound on sub-agent delegations the supervisor may make per run (Flow D). */
    public static final ConfigKey<Integer> SUPERVISOR_MAX_WORKERS =
            new ConfigKey<>("eoiagent.runtime.supervisor.maxWorkers", Integer.class, 3);
}
