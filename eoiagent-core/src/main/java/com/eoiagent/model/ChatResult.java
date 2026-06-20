package com.eoiagent.model;

import com.eoiagent.core.ToolCall;
import java.util.List;

/** The result of a chat completion: text, requested tool calls, model and usage. */
public record ChatResult(String text, List<ToolCall> toolCalls, ModelInfo model, Usage usage) {
}
