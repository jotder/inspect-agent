package com.eoiagent.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Meta-key convention for carrying tool-call turns inside a chat history record's
 * {@code Map<String,String>} meta (T-350). An ASSISTANT record that requested tool calls stores
 * them under {@link #TOOL_CALLS} as a JSON array of {@code {"id","name","arguments"}}; a TOOL
 * record stores {@link #TOOL_CALL_ID}/{@link #TOOL_NAME} so the result can be replayed to the
 * provider paired with the request that caused it (OpenAI-protocol servers reject unpaired tool
 * results). Encoding is pure JDK here in core; decoding lives with the model adapter, which has a
 * JSON parser.
 */
public final class ToolCallMeta {

    public static final String TOOL_CALLS = "toolCalls";
    public static final String TOOL_CALL_ID = "toolCallId";
    public static final String TOOL_NAME = "toolName";

    private ToolCallMeta() {
    }

    /** Meta for the ASSISTANT turn that requested {@code calls}. */
    public static Map<String, String> encode(List<ToolCall> calls) {
        Objects.requireNonNull(calls, "calls");
        StringBuilder sb = new StringBuilder(64).append('[');
        for (int i = 0; i < calls.size(); i++) {
            ToolCall c = calls.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":");
            string(sb, c.callId() == null ? c.toolName() : c.callId());
            sb.append(",\"name\":");
            string(sb, c.toolName());
            sb.append(",\"arguments\":");
            value(sb, c.arguments() == null ? Map.of() : c.arguments());
            sb.append('}');
        }
        return Map.of(TOOL_CALLS, sb.append(']').toString());
    }

    /** Meta for the TOOL turn carrying the result of {@code call}. */
    public static Map<String, String> forResult(ToolCall call) {
        Objects.requireNonNull(call, "call");
        return Map.of(
                TOOL_CALL_ID, call.callId() == null ? call.toolName() : call.callId(),
                TOOL_NAME, call.toolName());
    }

    // Minimal JSON writer for tool arguments (String/Number/Boolean/Map/List/null) — same
    // pure-JDK approach as the audit sink; anything else falls back to its toString as a string.
    private static void value(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    value(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    value(sb, l.get(i));
                }
                sb.append(']');
            }
            default -> string(sb, String.valueOf(v));
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
