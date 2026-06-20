package com.eoiagent.core;

import java.util.Map;

/** The outcome of a tool invocation: success flag, value or error, and metadata. */
public record ToolResult(boolean ok, Object value, String error, Map<String, Object> meta) {
}
