package com.eoiagent.memory;

import java.time.Instant;
import java.util.Map;

/** A single stored chat message with role, text, timestamp and metadata. */
public record ChatMessageRecord(ChatRole role, String text, Instant at, Map<String, String> meta) {
}
