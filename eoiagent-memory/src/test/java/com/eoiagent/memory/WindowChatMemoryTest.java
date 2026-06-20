package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WindowChatMemory bounds message count, preserves the system message, evicts oldest first (T-108 AC1). */
class WindowChatMemoryTest {

    private static final SessionId SESSION = new SessionId("s1");

    private static List<String> texts(WindowChatMemory memory) {
        return ChatMessageMapper.toRecords(memory.messages()).stream().map(ChatMessageRecord::text).toList();
    }

    @Test
    void retainsAtMostMaxMessagesPreservingSystemAndEvictingOldest() {
        WindowChatMemory memory = new WindowChatMemory(SESSION, 3, new InMemoryMemoryStore());

        memory.add(SystemMessage.from("sys"));
        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2"));
        memory.add(AiMessage.from("a2"));

        assertThat(memory.messages()).hasSizeLessThanOrEqualTo(3);
        assertThat(texts(memory)).contains("sys", "a2"); // system kept, newest kept
        assertThat(texts(memory)).doesNotContain("u1"); // oldest non-system evicted
    }

    @Test
    void persistsThroughTheBackingStore() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        WindowChatMemory memory = new WindowChatMemory(SESSION, 10, store);

        memory.add(UserMessage.from("remember me"));

        assertThat(store.get(SESSION)).extracting(ChatMessageRecord::text).contains("remember me");
    }
}
