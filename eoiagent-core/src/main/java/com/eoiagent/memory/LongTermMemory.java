package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import java.util.List;

/** Port for durable long-term memory of facts across sessions. */
public interface LongTermMemory {

    void remember(SessionId scope, MemoryFact fact);

    List<MemoryFact> recall(String query, int k);
}
