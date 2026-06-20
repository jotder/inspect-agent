package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** TokenWindowChatMemory bounds the conversation by a token budget using the offline heuristic (T-108 AC1). */
class TokenWindowChatMemoryTest {

    private static final SessionId SESSION = new SessionId("s1");
    private final HeuristicTokenCountEstimator tokenizer = new HeuristicTokenCountEstimator();

    @Test
    void keepsTotalTokensWithinBudget() {
        int maxTokens = 20;
        TokenWindowChatMemory memory =
                new TokenWindowChatMemory(SESSION, maxTokens, tokenizer, new InMemoryMemoryStore());

        for (int i = 0; i < 12; i++) {
            memory.add(UserMessage.from("message number " + i)); // ~16 chars -> ~4 tokens + overhead
        }

        assertThat(tokenizer.estimateTokenCountInMessages(memory.messages())).isLessThanOrEqualTo(maxTokens);
        assertThat(memory.messages()).isNotEmpty();
    }

    @Test
    void singleOversizedMessageIsHandledWithoutThrowing() { // spec AC2 — oversized handled, not thrown
        int maxTokens = 10;
        TokenWindowChatMemory memory =
                new TokenWindowChatMemory(SESSION, maxTokens, tokenizer, new InMemoryMemoryStore());
        String huge = "x".repeat(10_000);

        // LC4j evicts an over-budget message rather than throwing; the budget invariant still holds.
        assertThatCode(() -> memory.add(UserMessage.from(huge))).doesNotThrowAnyException();
        assertThat(tokenizer.estimateTokenCountInMessages(memory.messages())).isLessThanOrEqualTo(maxTokens);
    }
}
