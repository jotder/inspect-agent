package com.eoiagent.memory;

import com.eoiagent.core.SessionId;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default offline {@link MemoryStore}: a per-session snapshot of chat messages in a
 * {@link ConcurrentMap}. {@code put} replaces the stored list (last-write-wins, not append);
 * {@code get} returns an immutable copy, or an empty (never null) list for an unknown session;
 * {@code delete} is idempotent. Safe for concurrent access on distinct {@link SessionId}s.
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final ConcurrentMap<SessionId, List<ChatMessageRecord>> sessions = new ConcurrentHashMap<>();

    @Override
    public void put(SessionId id, List<ChatMessageRecord> messages) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(messages, "messages");
        sessions.put(id, List.copyOf(messages)); // immutable snapshot
    }

    @Override
    public List<ChatMessageRecord> get(SessionId id) {
        Objects.requireNonNull(id, "id");
        return sessions.getOrDefault(id, List.of());
    }

    @Override
    public void delete(SessionId id) {
        Objects.requireNonNull(id, "id");
        sessions.remove(id); // idempotent
    }
}
