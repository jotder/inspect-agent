package com.eoiagent.memory;

import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;

import java.util.ArrayList;
import java.util.List;

/**
 * In-test {@link LlmGateway} whose {@code embed} is a deterministic keyword-presence vector over a
 * tiny fixed vocabulary (last dimension is a constant 1 so no vector is ever all-zero). Texts that
 * share vocabulary words are more cosine-similar — enough to test relevance ranking offline.
 */
final class KeywordEmbeddingGateway implements LlmGateway {

    private static final ModelInfo MODEL = new ModelInfo("keyword-stub", "stub", true);
    private static final String[] VOCAB = {"orders", "pipeline", "schema", "dark", "theme", "deploy"};

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        List<float[]> vectors = new ArrayList<>();
        for (String input : request.inputs()) {
            String lower = input.toLowerCase();
            float[] v = new float[VOCAB.length + 1];
            for (int i = 0; i < VOCAB.length; i++) {
                v[i] = lower.contains(VOCAB[i]) ? 1f : 0f;
            }
            v[VOCAB.length] = 1f;
            vectors.add(v);
        }
        return new EmbeddingResult(vectors, MODEL);
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public void chatStream(ChatRequest request, TokenSink sink) {
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
