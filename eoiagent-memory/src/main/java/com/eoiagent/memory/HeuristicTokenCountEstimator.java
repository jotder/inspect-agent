package com.eoiagent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * Offline fallback tokenizer for {@link TokenWindowChatMemory} when the active chat model exposes no
 * tokenizer (e.g. in {@code OFFLINE} or under stub models). Approximates ~4 characters per token plus
 * a small per-message overhead. Deliberately rough — it bounds the window conservatively without any
 * model or network dependency; a real model tokenizer should be preferred when available.
 */
public final class HeuristicTokenCountEstimator implements TokenCountEstimator {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int PER_MESSAGE_OVERHEAD = 3;

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN; // ceil
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return PER_MESSAGE_OVERHEAD + estimateTokenCountInText(ChatMessageMapper.toRecord(message).text());
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimateTokenCountInMessage(m);
        }
        return total;
    }
}
