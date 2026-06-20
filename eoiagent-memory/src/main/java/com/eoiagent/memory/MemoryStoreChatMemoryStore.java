package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;
import java.util.Objects;

/**
 * Bridges LangChain4j's {@link ChatMemoryStore} to our {@link MemoryStore}: LC4j runs windowing /
 * eviction in front, while persistence is delegated to our store (in-memory, file, or Postgres). The
 * LC4j {@code memoryId} is the {@link SessionId}'s value. Persistence is snapshot semantics
 * (last-write-wins) — LC4j calls {@code updateMessages} with the full current list after each change.
 */
final class MemoryStoreChatMemoryStore implements ChatMemoryStore {

    private final MemoryStore store;

    MemoryStoreChatMemoryStore(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return ChatMessageMapper.toLc4j(store.get(sessionId(memoryId)));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.put(sessionId(memoryId), ChatMessageMapper.toRecords(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.delete(sessionId(memoryId));
    }

    private static SessionId sessionId(Object memoryId) {
        return memoryId instanceof SessionId sid ? sid : new SessionId(String.valueOf(memoryId));
    }
}
