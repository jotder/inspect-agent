package com.eoiagent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts our persistence-friendly {@link ChatMessageRecord}s to/from LangChain4j {@code ChatMessage}s
 * at the {@code ChatMemoryStore} boundary, so the store never depends on LC4j message classes leaking
 * into {@code core}. Role and text round-trip faithfully for every {@link ChatRole}.
 *
 * <p>NOTE: LC4j messages carry no timestamp/metadata slot, so a record routed <em>through</em> an
 * LC4j {@code ChatMemory} loses its original {@code at}/{@code meta} on the way back ({@code at} is
 * reset to now, {@code meta} to empty). The direct {@link MemoryStore} put/get path preserves them
 * in full (memory spec AC6).
 */
final class ChatMessageMapper {

    private ChatMessageMapper() {
    }

    static ChatMessage toLc4j(ChatMessageRecord r) {
        String text = r.text() == null ? "" : r.text();
        return switch (r.role()) {
            case SYSTEM -> SystemMessage.from(text);
            case USER -> UserMessage.from(text);
            case ASSISTANT -> AiMessage.from(text);
            case TOOL -> ToolExecutionResultMessage.from("tool", "tool", text);
        };
    }

    static List<ChatMessage> toLc4j(List<ChatMessageRecord> records) {
        List<ChatMessage> out = new ArrayList<>();
        if (records != null) {
            for (ChatMessageRecord r : records) {
                out.add(toLc4j(r));
            }
        }
        return out;
    }

    static ChatMessageRecord toRecord(ChatMessage m) {
        return new ChatMessageRecord(role(m), text(m), Instant.now(), Map.of());
    }

    static List<ChatMessageRecord> toRecords(List<ChatMessage> messages) {
        List<ChatMessageRecord> out = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage m : messages) {
                out.add(toRecord(m));
            }
        }
        return out;
    }

    private static ChatRole role(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> ChatRole.SYSTEM;
            case USER -> ChatRole.USER;
            case AI -> ChatRole.ASSISTANT;
            case TOOL_EXECUTION_RESULT -> ChatRole.TOOL;
            case CUSTOM -> ChatRole.USER; // no domain equivalent; carry the text as a user message
        };
    }

    private static String text(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> ((SystemMessage) m).text();
            case USER -> ((UserMessage) m).singleText();
            case AI -> ((AiMessage) m).text();
            case TOOL_EXECUTION_RESULT -> ((ToolExecutionResultMessage) m).text();
            case CUSTOM -> "";
        };
    }
}
