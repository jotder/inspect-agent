package com.eoiagent.core;

import java.util.Map;

/** A request to invoke a named tool with arguments, scoped to a run. */
public record ToolCall(String toolName, Map<String, Object> arguments, RunId run) {
}
