package com.eoiagent.core;

import java.util.Map;

/** The result of dry-running a tool call: whether supported, a preview and predicted effects. */
public record DryRunResult(boolean supported, String preview, Map<String, Object> predictedEffects) {
}
