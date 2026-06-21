package com.eoiagent.app;

import com.eoiagent.tool.Tool;
import java.util.List;

/**
 * The host Java-API tools and external MCP servers a pack exposes to the {@code ToolRegistry}. Both
 * lists may be empty. Each {@link Tool} declares {@code mutating} + {@code requiredRole} in its
 * {@code ToolSpec}; MCP entries are gated by {@code Feature.MCP_TOOLS} + profile.
 */
public interface ToolProvider {

    /** Host {@code @Tool}-backed tools. May be empty; no null elements. */
    List<Tool> tools();

    /** External MCP tool servers. May be empty. */
    List<McpServerRef> mcpServers();
}
