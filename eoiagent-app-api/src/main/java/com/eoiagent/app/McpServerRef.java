package com.eoiagent.app;

/** A reference to an external MCP tool server: its id, transport (e.g. stdio/http) and target. */
public record McpServerRef(String id, String transport, String target) {
}
