package com.eoiagent.scratchpad;

/**
 * Thrown by {@link Scratchpad#read(String)} for an unknown key/handle. The port never returns null,
 * so callers (the orchestrator) treat this as a recoverable observation rather than a crash.
 */
public final class ScratchpadKeyNotFound extends ScratchpadException {

    public ScratchpadKeyNotFound(String key) {
        super("no scratchpad entry for key: " + key);
    }
}
