package com.eoiagent.tool;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolExecutionException;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.internal.Json;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a tool advertised by an external MCP server as an EOI {@link Tool}. Implements the
 * {@link McpTool} marker so the {@link DefaultToolRegistry} gates it on the {@code MCP_TOOLS} feature
 * (hidden / fail-closed when disabled — no network). The {@link ToolSpec} name, description and JSON
 * schema are derived from the MCP {@link ToolSpecification} (same mapping as {@link JavaApiTool});
 * the classification ({@code mutating}, {@code requiredRole}, {@code capability}) is supplied by the
 * host at registration, since MCP does not carry it.
 *
 * <p>The {@code langchain4j-mcp} dependency is experimental and quarantined to this adapter
 * (ADR-0010). {@link #invoke} delegates to {@link McpClient#executeTool}; an error result maps to
 * {@code ToolResult{ok=false}}, an unexpected fault to {@link ToolExecutionException}.
 */
public final class McpToolAdapter implements McpTool {

    private final McpClient client;
    private final String mcpToolName;
    private final ToolSpec spec;

    public McpToolAdapter(McpClient client, ToolSpecification toolSpec,
                          boolean mutating, Role requiredRole, Capability capability) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(toolSpec, "toolSpec");
        Objects.requireNonNull(requiredRole, "requiredRole");
        Objects.requireNonNull(capability, "capability");
        this.mcpToolName = toolSpec.name();
        this.spec = new ToolSpec(toolSpec.name(), toolSpec.description(),
                schemaJson(toolSpec.parameters()), mutating, requiredRole, capability);
    }

    /** Wrap every tool the MCP {@code client} advertises with the given host classification. */
    public static List<McpToolAdapter> fromClient(McpClient client, boolean mutating,
                                                  Role requiredRole, Capability capability) {
        Objects.requireNonNull(client, "client");
        return client.listTools().stream()
                .map(ts -> new McpToolAdapter(client, ts, mutating, requiredRole, capability))
                .toList();
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        Objects.requireNonNull(call, "call");
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(mcpToolName)
                .arguments(Json.toJson(args))
                .build();
        try {
            ToolExecutionResult result = client.executeTool(request);
            if (result.isError()) {
                // An expected tool-side failure — surface as ToolResult, not an exception.
                return new ToolResult(false, null, result.resultText(), Map.of());
            }
            return new ToolResult(true, result.resultText(), null, Map.of());
        } catch (RuntimeException e) {
            throw new ToolExecutionException("MCP tool '" + spec.name() + "' failed to execute", e);
        }
    }

    private static String schemaJson(JsonObjectSchema parameters) {
        if (parameters == null) {
            return "{}"; // no-arg tool
        }
        return Json.toJson(JsonSchemaElementJsonUtils.toMap(parameters));
    }
}
