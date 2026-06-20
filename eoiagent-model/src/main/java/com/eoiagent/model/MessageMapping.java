package com.eoiagent.model;

import com.eoiagent.memory.ChatMessageRecord;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** Maps our {@link ChatMessageRecord}s to LangChain4j {@code ChatMessage}s. */
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
            switch (m.role()) {
                case SYSTEM -> out.add(SystemMessage.from(text));
                case USER -> out.add(UserMessage.from(text));
                case ASSISTANT -> out.add(AiMessage.from(text));
                case TOOL -> out.add(UserMessage.from(text)); // tool-result round-trip deferred
            }
        }
        return out;
    }
}
