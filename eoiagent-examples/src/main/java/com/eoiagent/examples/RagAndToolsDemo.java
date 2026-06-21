package com.eoiagent.examples;

import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Showcases the pack's knowledge corpus and read-only tools. Lists the bundled {@link KnowledgeSource}s
 * the core ingestor would load, then invokes each {@link Tool} with sample arguments and prints the
 * result — including a tool's graceful {@code ok=false} response for an unknown id (tools never throw).
 *
 * <p>These invocations call the tools directly to show their behaviour; in a real run the
 * {@code ToolRegistry} would gate every call by role/capability/profile first.
 */
public final class RagAndToolsDemo {

    private RagAndToolsDemo() {
    }

    public static void main(String[] args) {
        ReferenceApplicationPack pack = new ReferenceApplicationPack();

        DemoSupport.header("Knowledge corpus (RAG sources)");
        for (KnowledgeSource source : pack.knowledgeSources()) {
            DemoSupport.kv(source.id(), source.kind() + " - " + describe(source.resolve()));
        }

        DemoSupport.header("Read-only tools");
        List<Tool> tools = pack.toolProvider().tools();
        for (Tool tool : tools) {
            DemoSupport.kv(tool.spec().name(), "role=" + tool.spec().requiredRole()
                    + ", capability=" + tool.spec().capability() + ", mutating=" + tool.spec().mutating());
        }

        DemoSupport.header("Invoking tools with sample arguments");
        RunId run = new RunId("demo-run");
        invoke(tool(tools, "listDatasets"), new ToolCall("listDatasets", Map.of("zone", "curated"), run));
        invoke(tool(tools, "describeSchema"), new ToolCall("describeSchema", Map.of("dataset", "orders"), run));
        invoke(tool(tools, "getPipelineStatus"), new ToolCall("getPipelineStatus", Map.of("pipelineId", "nightly-load"), run));
        System.out.println("  (expected failure - unknown dataset, returned not thrown):");
        invoke(tool(tools, "describeSchema"), new ToolCall("describeSchema", Map.of("dataset", "nope"), run));
    }

    private static void invoke(Tool tool, ToolCall call) {
        ToolResult result = tool.invoke(call);
        if (result.ok()) {
            DemoSupport.bullet(call.toolName() + "(" + call.arguments() + ") -> " + result.value());
        } else {
            DemoSupport.bullet(call.toolName() + "(" + call.arguments() + ") -> ERROR: " + result.error());
        }
    }

    private static Tool tool(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    private static String describe(List<DocumentSource> docs) {
        return docs.size() + " document(s): " + docs.stream().map(DocumentSource::uri).toList();
    }
}
