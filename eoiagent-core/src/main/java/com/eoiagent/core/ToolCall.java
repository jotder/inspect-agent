package com.eoiagent.core;

import java.util.Map;

/**
 * A request to invoke a named tool with arguments, scoped to a run. {@code callId} is the
 * provider-assigned tool-call id (OpenAI-protocol servers require the tool result to echo it);
 * null when the caller/model supplies none (T-350).
 */
public record ToolCall(String callId, String toolName, Map<String, Object> arguments, RunId run) {

    /** Id-less variant — pre-T-350 shape, still valid wherever no provider call id exists. */
    public ToolCall(String toolName, Map<String, Object> arguments, RunId run) {
        this(null, toolName, arguments, run);
    }
}
