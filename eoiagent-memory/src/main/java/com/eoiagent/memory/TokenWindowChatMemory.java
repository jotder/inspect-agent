package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.List;
import java.util.Objects;

/**
 * Short-term memory bounded by a token budget rather than a message count, delegating eviction to
 * LangChain4j's {@code TokenWindowChatMemory} and persistence to a {@link MemoryStore}. Token counts
 * come from the supplied {@link TokenCountEstimator} — the active model's tokenizer, or the offline
 * {@link HeuristicTokenCountEstimator} fallback. A single message larger than the budget is retained
 * (LC4j keeps the most recent message) rather than throwing.
 */
public final class TokenWindowChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public TokenWindowChatMemory(SessionId session, int maxTokens, TokenCountEstimator tokenizer, MemoryStore store) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tokenizer, "tokenizer");
        this.delegate = dev.langchain4j.memory.chat.TokenWindowChatMemory.builder()
                .id(session.value())
                .maxTokens(maxTokens, tokenizer)
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
