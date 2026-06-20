package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** InMemoryMemoryStore round-trip, snapshot, empty-on-unknown and idempotent-delete (T-108 AC2). */
class InMemoryMemoryStoreTest {

    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");

    private static ChatMessageRecord msg(ChatRole role, String text) {
        return new ChatMessageRecord(role, text, Instant.EPOCH, Map.of());
    }

    @Test
    void putThenGetRoundTrips() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        List<ChatMessageRecord> messages = List.of(msg(ChatRole.USER, "hi"), msg(ChatRole.ASSISTANT, "hello"));

        store.put(S1, messages);

        assertThat(store.get(S1)).isEqualTo(messages);
    }

    @Test
    void getUnknownSessionReturnsEmptyNonNullList() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        assertThat(store.get(S1)).isNotNull().isEmpty();
    }

    @Test
    void putIsSnapshotNotAppend() { // AC5 — replaces, not appends
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.put(S1, List.of(
                msg(ChatRole.USER, "a"), msg(ChatRole.ASSISTANT, "b"),
                msg(ChatRole.USER, "c"), msg(ChatRole.ASSISTANT, "d"),
                msg(ChatRole.USER, "e")));

        store.put(S1, List.of(msg(ChatRole.USER, "x"), msg(ChatRole.ASSISTANT, "y")));

        assertThat(store.get(S1)).hasSize(2);
    }

    @Test
    void distinctSessionsAreIsolated() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.put(S1, List.of(msg(ChatRole.USER, "one")));
        store.put(S2, List.of(msg(ChatRole.USER, "two")));

        assertThat(store.get(S1)).extracting(ChatMessageRecord::text).containsExactly("one");
        assertThat(store.get(S2)).extracting(ChatMessageRecord::text).containsExactly("two");
    }

    @Test
    void deleteIsIdempotent() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.put(S1, List.of(msg(ChatRole.USER, "hi")));

        store.delete(S1);
        store.delete(S1); // no-op, no throw

        assertThat(store.get(S1)).isEmpty();
    }
}
