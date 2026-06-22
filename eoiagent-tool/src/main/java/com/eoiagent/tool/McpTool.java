package com.eoiagent.tool;

/**
 * Marker for a {@link Tool} backed by an external MCP server (Model Context Protocol). The
 * {@link DefaultToolRegistry} gates these on the {@code MCP_TOOLS} feature: an {@code McpTool} is
 * hidden from {@code visibleTo} and fails closed on {@code dispatch} (no network) unless
 * {@code MCP_TOOLS} is enabled for the active profile (tool-registry spec AC9).
 */
public interface McpTool extends Tool {
}
