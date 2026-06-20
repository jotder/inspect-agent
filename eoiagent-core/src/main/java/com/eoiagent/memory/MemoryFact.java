package com.eoiagent.memory;

import com.eoiagent.core.SessionId;
import java.time.Instant;
import java.util.Map;

/** A durable fact remembered for a session scope. */
public record MemoryFact(SessionId scope, String text, Instant at, Map<String, String> meta) {
}
