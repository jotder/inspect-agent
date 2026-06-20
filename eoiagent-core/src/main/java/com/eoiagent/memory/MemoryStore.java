package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import java.util.List;

/** Port for short-term per-session chat message storage. */
public interface MemoryStore {

    void put(SessionId id, List<ChatMessageRecord> messages);

    List<ChatMessageRecord> get(SessionId id);

    void delete(SessionId id);
}
