package com.eoiagent.tool;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * McpToolAdapter end-to-end against a real stdio MCP server (T-209). Connects to the reference
 * "everything" server over stdio, lists its tools, wraps them, and invokes {@code echo}.
 *
 * <p>Opt-in / env-gated (same pattern as the other integration tests): skipped unless
 * {@code EOIAGENT_IT_MCP} is set, so the default offline build stays green (and doesn't shell out to
 * npx). Run with Node on PATH: {@code EOIAGENT_IT_MCP=1 mvn -pl eoiagent-tool test}.
 */
@EnabledIfEnvironmentVariable(named = "EOIAGENT_IT_MCP", matches = ".+")
class McpToolAdapterStdioTest {

    private static McpClient client;

    @BeforeAll
    static void startServer() {
        // Launch via cmd.exe so the Windows npx shim resolves; npx downloads the package on first run.
        List<String> command = List.of("cmd.exe", "/c", "npx", "-y", "@modelcontextprotocol/server-everything");
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(command)
                .logEvents(false)
                .build();
        client = new DefaultMcpClient.Builder()
                .transport(transport)
                .key("everything")
                .build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void wrapsAdvertisedToolsAndInvokesEcho() {
        List<McpToolAdapter> tools = McpToolAdapter.fromClient(client, false, Role.ANALYST, Capability.READ_DOCS);

        assertThat(tools).isNotEmpty();
        McpToolAdapter echo = tools.stream()
                .filter(t -> t.spec().name().equals("echo"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("server-everything should advertise an 'echo' tool"));
        assertThat(echo.spec().jsonSchema()).contains("message");
        assertThat(echo.spec().requiredRole()).isEqualTo(Role.ANALYST);

        ToolResult result = echo.invoke(new ToolCall("echo", Map.of("message", "hello mcp"), new RunId("r1")));

        assertThat(result.ok()).isTrue();
        assertThat(String.valueOf(result.value())).contains("hello mcp");
    }
}
