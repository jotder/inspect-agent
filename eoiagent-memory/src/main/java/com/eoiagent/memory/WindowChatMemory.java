package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.List;
import java.util.Objects;

/**
 * Short-term memory that keeps the last {@code maxMessages} messages for a {@link SessionId},
 * delegating eviction to LangChain4j's {@code MessageWindowChatMemory} and persistence to a
 * {@link MemoryStore} via {@link MemoryStoreChatMemoryStore}. The system message is preserved across
 * eviction; oldest non-system messages are dropped first.
 */
public final class WindowChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public WindowChatMemory(SessionId session, int maxMessages, MemoryStore store) {
        Objects.requireNonNull(session, "session");
        this.delegate = MessageWindowChatMemory.builder()
                .id(session.value())
                .maxMessages(maxMessages)
                .chatMemoryStore(new MemoryStoreChatMemoryStore(store))
                .build();
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        return delegate.messages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
