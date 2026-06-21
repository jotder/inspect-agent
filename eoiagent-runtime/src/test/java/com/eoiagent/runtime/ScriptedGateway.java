package com.eoiagent.runtime;

import com.eoiagent.core.ToolCall;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Flexible deterministic {@link LlmGateway} test double: enqueue tool-call / final-text turns, set a
 * looping reply (to drive maxSteps), or make {@code chat} throw (to drive the error path). Captures
 * the messages of the most recent request so tests can assert what the loop fed back to the model.
 */
final class ScriptedGateway implements LlmGateway {

    private static final ModelInfo MODEL = new ModelInfo("scripted", "stub", true);

    private final Deque<ChatResult> scripted = new ArrayDeque<>();
    private ChatResult loopReply;            // returned when the script is exhausted (maxSteps)
    private RuntimeException failure;        // if set, chat throws it
    List<com.eoiagent.memory.ChatMessageRecord> lastMessages = List.of();
    int chatCalls = 0;

    ScriptedGateway toolCall(String tool) {
        scripted.add(new ChatResult("", List.of(new ToolCall(tool, Map.of(), null)), MODEL, null));
        return this;
    }

    /** Enqueue a single turn carrying several tool calls at once (e.g. a planning response). */
    ScriptedGateway toolCalls(String... tools) {
        List<ToolCall> calls = new java.util.ArrayList<>();
        for (String tool : tools) {
            calls.add(new ToolCall(tool, Map.of(), null));
        }
        scripted.add(new ChatResult("", List.copyOf(calls), MODEL, null));
        return this;
    }

    ScriptedGateway finalText(String text) {
        scripted.add(new ChatResult(text, List.of(), MODEL, null));
        return this;
    }

    ScriptedGateway alwaysToolCall(String tool) {
        loopReply = new ChatResult("", List.of(new ToolCall(tool, Map.of(), null)), MODEL, null);
        return this;
    }

    ScriptedGateway failsWith(RuntimeException e) {
        failure = e;
        return this;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        chatCalls++;
        lastMessages = List.copyOf(request.messages());
        if (failure != null) {
            throw failure;
        }
        ChatResult next = scripted.poll();
        if (next != null) {
            return next;
        }
        if (loopReply != null) {
            return loopReply;
        }
        throw new IllegalStateException("ScriptedGateway: script exhausted");
    }

    @Override
    public void chatStream(ChatRequest request, TokenSink sink) {
        throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public ModelInfo activeChatModel() {
        return MODEL;
    }

    @Override
    public boolean isAvailable(ModelRole role) {
        return true;
    }
}
