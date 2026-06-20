package com.eoiagent.model;

import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import java.util.List;

/** A chat completion request: messages, available tools and options. */
public record ChatRequest(List<ChatMessageRecord> messages, List<ToolSpec> tools, ChatOptions options) {
}
