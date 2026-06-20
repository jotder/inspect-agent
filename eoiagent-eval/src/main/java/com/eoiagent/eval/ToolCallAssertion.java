package com.eoiagent.eval;

import java.util.Map;

/** An assertion that a named tool call occurred (or must be absent) with an args subset. */
public record ToolCallAssertion(String toolName, Map<String, Object> argsSubset, boolean mustBeAbsent) {
}
