package com.eoiagent.model;

import com.eoiagent.core.ToolCallMeta;
import com.eoiagent.memory.ChatMessageRecord;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps our {@link ChatMessageRecord}s to LangChain4j {@code ChatMessage}s. Tool turns round-trip
 * via the {@link ToolCallMeta} convention (T-350): an ASSISTANT record carrying tool calls becomes
 * an {@code AiMessage} with {@code ToolExecutionRequest}s, and a TOOL record carrying a call
 * id/name becomes a {@code ToolExecutionResultMessage} — the pairing OpenAI-protocol providers
 * require. Records without that meta keep their legacy mapping.
 */
final class MessageMapping {

    private MessageMapping() {
    }

    static List<ChatMessage> toLc4j(List<ChatMessageRecord> messages) {
        List<ChatMessage> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (ChatMessageRecord m : messages) {
            String text = m.text() == null ? "" : m.text();
            Map<String, String> meta = m.meta() == null ? Map.of() : m.meta();
            switch (m.role()) {
                case SYSTEM -> out.add(SystemMessage.from(text));
                case USER -> out.add(UserMessage.from(text));
                case ASSISTANT -> out.add(assistantMessage(text, meta.get(ToolCallMeta.TOOL_CALLS)));
                case TOOL -> out.add(toolMessage(text, meta));
            }
        }
        return out;
    }

    private static ChatMessage assistantMessage(String text, String toolCallsJson) {
        List<ToolExecutionRequest> requests = decodeToolCalls(toolCallsJson);
        if (requests.isEmpty()) {
            return AiMessage.from(text);
        }
        return text.isBlank() ? AiMessage.from(requests) : AiMessage.from(text, requests);
    }

    private static ChatMessage toolMessage(String text, Map<String, String> meta) {
        String name = meta.get(ToolCallMeta.TOOL_NAME);
        if (name == null) {
            return UserMessage.from(text); // legacy record without pairing info
        }
        String id = meta.getOrDefault(ToolCallMeta.TOOL_CALL_ID, name);
        return ToolExecutionResultMessage.from(id, name, text);
    }

    /** Decodes the {@link ToolCallMeta#TOOL_CALLS} JSON array; malformed meta degrades to none. */
    private static List<ToolExecutionRequest> decodeToolCalls(String json) {
        List<ToolExecutionRequest> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            List<?> calls = Json.fromJson(json, List.class);
            if (calls == null) {
                return out;
            }
            for (Object call : calls) {
                if (call instanceof Map<?, ?> m) {
                    String name = String.valueOf(m.get("name"));
                    Object id = m.get("id");
                    Object args = m.get("arguments");
                    out.add(ToolExecutionRequest.builder()
                            .id(id == null ? name : String.valueOf(id))
                            .name(name)
                            .arguments(Json.toJson(args == null ? Map.of() : args))
                            .build());
                }
            }
        } catch (RuntimeException e) {
            out.clear(); // fail-soft: better a text-only turn than a crashed chat call
        }
        return out;
    }
}
