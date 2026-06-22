package com.eoiagent.memory;

import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;

import java.util.List;

/**
 * In-test {@link LlmGateway}: returns a fixed summary text from {@code chat} (or throws a configured
 * fault to drive the fallback path), counting how many times the summarizer called the model.
 */
final class ScriptedSummaryGateway implements LlmGateway {

    private static final ModelInfo MODEL = new ModelInfo("scripted", "stub", true);

    private final String reply;             // returned from chat when no failure is configured
    private final RuntimeException failure; // if set, chat throws it
    int chatCalls = 0;

    ScriptedSummaryGateway(String reply) {
        this.reply = reply;
        this.failure = null;
    }

    ScriptedSummaryGateway(RuntimeException failure) {
        this.reply = null;
        this.failure = failure;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        chatCalls++;
        if (failure != null) {
            throw failure;
        }
        return new ChatResult(reply, List.of(), MODEL, null);
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
        return failure == null;
    }
}
