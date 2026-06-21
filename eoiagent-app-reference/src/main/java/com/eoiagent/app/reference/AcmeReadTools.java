package com.eoiagent.app.reference;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The three read-only Acme tools, implemented against the core {@link Tool} <em>port</em> (the pack
 * never depends on the {@code eoiagent-tool} adapter, so it cannot use {@code JavaApiTool}). Each
 * returns canned sample results so the demo runs without a live lakehouse, and reports an
 * {@code ok=false} {@link ToolResult} for an expected failure (unknown id) rather than throwing
 * (tool-registry contract). All are {@code mutating=false} with an honest {@code requiredRole} +
 * {@code Capability}.
 */
final class AcmeReadTools {

    private AcmeReadTools() {
    }

    static List<Tool> tools() {
        return List.of(listDatasets(), describeSchema(), getPipelineStatus());
    }

    private static Tool listDatasets() {
        ToolSpec spec = new ToolSpec("listDatasets", "List datasets in a lakehouse zone",
                "{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}}}",
                false, Role.USER, Capability.READ_METADATA);
        return new CannedTool(spec, call -> {
            String zone = arg(call, "zone", "curated");
            return ok(Map.of("zone", zone, "datasets", List.of("orders", "customers", "revenue_daily")));
        });
    }

    private static Tool describeSchema() {
        ToolSpec spec = new ToolSpec("describeSchema", "Columns + types for a dataset",
                "{\"type\":\"object\",\"properties\":{\"dataset\":{\"type\":\"string\"}},\"required\":[\"dataset\"]}",
                false, Role.ANALYST, Capability.READ_SCHEMA);
        return new CannedTool(spec, call -> {
            String dataset = arg(call, "dataset", "");
            return switch (dataset) {
                case "orders" -> ok(Map.of("dataset", "orders", "columns",
                        List.of("order_id:long", "customer_id:long", "amount:decimal", "created_at:timestamp")));
                case "customers" -> ok(Map.of("dataset", "customers", "columns",
                        List.of("customer_id:long", "name:string", "region:string")));
                default -> error("unknown dataset: '" + dataset + "'");
            };
        });
    }

    private static Tool getPipelineStatus() {
        ToolSpec spec = new ToolSpec("getPipelineStatus", "Last run status of an ETL pipeline",
                "{\"type\":\"object\",\"properties\":{\"pipelineId\":{\"type\":\"string\"}},\"required\":[\"pipelineId\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new CannedTool(spec, call -> {
            String pipelineId = arg(call, "pipelineId", "");
            if (pipelineId.isBlank()) {
                return error("pipelineId is required");
            }
            return ok(Map.of("pipelineId", pipelineId, "lastRun", "2026-06-20T02:00:00Z",
                    "status", "SUCCEEDED", "rows", 1_280_344));
        });
    }

    private static String arg(ToolCall call, String key, String fallback) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static ToolResult ok(Object value) {
        return new ToolResult(true, value, null, Map.of());
    }

    private static ToolResult error(String message) {
        return new ToolResult(false, null, message, Map.of());
    }

    /** A {@link Tool} whose behaviour is a fixed spec plus a pure function over the call. */
    private record CannedTool(ToolSpec spec, Function<ToolCall, ToolResult> body) implements Tool {
        @Override
        public ToolResult invoke(ToolCall call) {
            return body.apply(Objects.requireNonNull(call, "call"));
        }
    }
}
