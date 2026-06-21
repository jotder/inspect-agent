package com.eoiagent.app.reference;

import com.eoiagent.app.McpServerRef;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.tool.Tool;

import java.util.List;

/**
 * Exposes the three read-only Acme tools to the core {@code ToolRegistry}. No MCP servers in OFFLINE
 * (MCP is gated off by {@code Feature.MCP_TOOLS} + profile anyway).
 */
final class ReferenceToolProvider implements ToolProvider {

    @Override
    public List<Tool> tools() {
        return AcmeReadTools.tools();
    }

    @Override
    public List<McpServerRef> mcpServers() {
        return List.of();
    }
}
