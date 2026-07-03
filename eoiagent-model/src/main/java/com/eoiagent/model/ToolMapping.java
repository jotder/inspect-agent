package com.eoiagent.model;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps tool declarations and tool calls across the LangChain4j seam (T-350), model-agnostically —
 * the provider integration (Ollama, OpenAI-compatible) is LC4j's job; ours is a faithful
 * translation of {@link ToolSpec}/{@link ToolCall}. Uses LC4j {@code internal.*} JSON utilities,
 * the same pragmatic, BOM-pinned choice the tool registry already made (see {@code JavaApiTool}):
 * {@code JsonSchemaElementJsonUtils.fromMap} is the exact inverse of the {@code toMap} that
 * produced {@link ToolSpec#jsonSchema()} there.
 */
final class ToolMapping {

    /** Key under which unparseable model-emitted argument JSON is surfaced (never thrown away). */
    static final String RAW_ARGUMENTS_KEY = "_raw";

    private ToolMapping() {
    }

    /** Our declarative specs → LC4j tool specifications sent with the chat request. */
    static List<ToolSpecification> toLc4j(List<ToolSpec> tools) {
        List<ToolSpecification> out = new ArrayList<>();
        if (tools == null) {
            return out;
        }
        for (ToolSpec spec : tools) {
            ToolSpecification.Builder b = ToolSpecification.builder()
                    .name(spec.name())
                    .description(spec.description());
            JsonObjectSchema params = parseSchema(spec.jsonSchema());
            if (params != null) {
                b.parameters(params);
            }
            out.add(b.build());
        }
        return out;
    }

    /** The model's tool requests → our calls (unscoped; the orchestrator scopes them to a run). */
    static List<ToolCall> toToolCalls(AiMessage ai) {
        List<ToolCall> out = new ArrayList<>();
        if (ai == null || !ai.hasToolExecutionRequests()) {
            return out;
        }
        for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
            out.add(new ToolCall(req.id(), req.name(), parseArguments(req.arguments()), null));
        }
        return out;
    }

    /** Our call → an LC4j request, for replaying an ASSISTANT tool-call turn back to the model. */
    static ToolExecutionRequest toLc4j(ToolCall call) {
        return ToolExecutionRequest.builder()
                .id(call.callId() == null ? call.toolName() : call.callId())
                .name(call.toolName())
                .arguments(Json.toJson(call.arguments() == null ? Map.of() : call.arguments()))
                .build();
    }

    private static JsonObjectSchema parseSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = Json.fromJson(jsonSchema, Map.class);
            JsonSchemaElement element = JsonSchemaElementJsonUtils.fromMap(map);
            return element instanceof JsonObjectSchema obj ? obj : null;
        } catch (RuntimeException e) {
            return null; // schema-less spec is still callable; validation happens at dispatch
        }
    }

    /**
     * Parses model-emitted argument JSON. Small local models sometimes emit malformed JSON — that
     * must not crash the gateway; the raw string is preserved under {@link #RAW_ARGUMENTS_KEY} so
     * dispatch-side validation fails with detail instead.
     */
    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = Json.fromJson(arguments, Map.class);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of(RAW_ARGUMENTS_KEY, arguments);
        }
    }
}
