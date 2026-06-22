package com.eoiagent.examples;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.safety.RoleBasedPolicyEngine;
import com.eoiagent.tool.DefaultToolRegistry;
import com.eoiagent.tool.McpTool;

import java.util.List;
import java.util.Map;

/**
 * Phase-2 — MCP tool gating (T-209). A tool backed by an external MCP server is marked with the
 * {@link McpTool} interface; the {@link DefaultToolRegistry} gates it on the {@code MCP_TOOLS} feature.
 * When the feature is off, the tool is hidden from {@code visibleTo} and {@code dispatch} fails closed
 * with a {@link PolicyViolation} (an MCP call would reach the network) — it is never invoked. When the
 * feature is on (only off-OFFLINE, where network egress is permitted) the tool is visible and
 * dispatchable.
 *
 * <p>This runs offline against a stand-in {@link DemoMcpTool} (no live server) to show the gating
 * decision itself; a real adapter ({@code McpToolAdapter}) wraps a langchain4j {@code McpClient}.
 */
public final class McpGatingDemo {

    private McpGatingDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("MCP tool gating (MCP_TOOLS feature)");

        // MCP disabled (e.g. OFFLINE): hidden + fail-closed.
        DemoMcpTool offTool = new DemoMcpTool();
        DefaultToolRegistry off = new DefaultToolRegistry(
                new RoleBasedPolicyEngine(), null, new DemoConfig(DeploymentProfile.OFFLINE), new ConsoleAuditSink());
        off.register(offTool);
        AgentContext offlineCtx = DemoSupport.context(Role.ADMIN, DeploymentProfile.OFFLINE);

        System.out.println();
        DemoSupport.bullet("MCP_TOOLS disabled (OFFLINE)");
        DemoSupport.kv("  visible tools", names(off.visibleTo(offlineCtx)));
        try {
            off.dispatch(new ToolCall("remote_echo", Map.of("text", "hi"), new RunId("mcp-off")), offlineCtx);
            DemoSupport.kv("  dispatch", "unexpectedly succeeded");
        } catch (PolicyViolation e) {
            DemoSupport.kv("  dispatch", "fail-closed -> " + e.getMessage());
        }
        DemoSupport.kv("  server invoked", offTool.invoked);

        // MCP enabled (off-OFFLINE): visible + dispatchable.
        DemoMcpTool onTool = new DemoMcpTool();
        DefaultToolRegistry on = new DefaultToolRegistry(
                new RoleBasedPolicyEngine(), null,
                new DemoConfig(DeploymentProfile.ON_PREM_HOSTED, Feature.MCP_TOOLS), new ConsoleAuditSink());
        on.register(onTool);
        AgentContext hostedCtx = DemoSupport.context(Role.ADMIN, DeploymentProfile.ON_PREM_HOSTED);

        System.out.println();
        DemoSupport.bullet("MCP_TOOLS enabled (ON_PREM_HOSTED)");
        DemoSupport.kv("  visible tools", names(on.visibleTo(hostedCtx)));
        ToolResult result = on.dispatch(new ToolCall("remote_echo", Map.of("text", "hi"), new RunId("mcp-on")), hostedCtx);
        DemoSupport.kv("  dispatch", result.ok() ? "OK -> " + result.value() : "ERROR -> " + result.error());
        DemoSupport.kv("  server invoked", onTool.invoked);
    }

    private static List<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    /** Stand-in for an MCP-backed tool (a real one is an {@code McpToolAdapter} over an {@code McpClient}). */
    private static final class DemoMcpTool implements McpTool {

        private final ToolSpec spec = new ToolSpec("remote_echo", "Echo text via an external MCP server",
                "{}", false, Role.USER, Capability.READ_DOCS);
        private boolean invoked;

        @Override
        public ToolSpec spec() {
            return spec;
        }

        @Override
        public ToolResult invoke(ToolCall call) {
            invoked = true;
            return new ToolResult(true, "echo: " + call.arguments().getOrDefault("text", ""), null, Map.of());
        }
    }
}
