package com.eoiagent.model;

import com.eoiagent.core.ToolCall;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic, in-memory {@link LlmGateway} for tests — no network, no live model. Chat responses
 * are scripted and consumed FIFO (so a ReAct loop can be driven: tool-call turn, then final answer);
 * embeddings are derived deterministically from the input text. This is the default gateway for all
 * LLM-dependent tests and the eval harness's OFFLINE leg (model-gateway spec; eval-harness AC6).
 *
 * <p>Build with {@link #builder()}:
 * <pre>{@code
 * StubLlmGateway g = StubLlmGateway.builder()
 *     .replyToolCalls(new ToolCall("list_runs", Map.of(), run))  // first chat() turn
 *     .replyText("the pipeline failed at step 3")                // second chat() turn
 *     .build();
 * }</pre>
 */
public final class StubLlmGateway implements LlmGateway {

    private final Deque<ChatResult> scriptedChats;
    private final ChatResult defaultReply; // nullable: used when the script is exhausted
    private final ModelInfo model;
    private final int embeddingDim;
    private ModelInfo active;

    private StubLlmGateway(Builder b) {
        this.scriptedChats = new ArrayDeque<>(b.chats);
        this.defaultReply = b.defaultReply;
        this.model = b.model;
        this.embeddingDim = b.embeddingDim;
        this.active = b.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        ChatResult result = nextChat();
        this.active = result.model() != null ? result.model() : model;
        return result;
    }

    @Override
    public void chatStream(ChatRequest request, TokenSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        ChatResult result;
        try {
            result = nextChat();
        } catch (RuntimeException e) {
            sink.onError(e);
            return;
        }
        String text = result.text() == null ? "" : result.text();
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty()) {
                sink.onToken(token);
            }
        }
        this.active = result.model() != null ? result.model() : model;
        sink.onComplete(result);
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> inputs = request.inputs();
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("EmbeddingRequest.inputs() must be non-empty");
        }
        List<float[]> vectors = inputs.stream()
                .map(this::deterministicVector)
                .toList();
        return new EmbeddingResult(vectors, model);
    }

    @Override
    public ModelInfo activeChatModel() {
        return active;
    }

    @Override
    public boolean isAvailable(ModelRole role) {
        // A stub is always reachable and never touches the network.
        return role != null;
    }

    private ChatResult nextChat() {
        ChatResult next = scriptedChats.poll();
        if (next != null) {
            return next;
        }
        if (defaultReply != null) {
            return defaultReply;
        }
        throw new IllegalStateException(
                "StubLlmGateway: no scripted chat response left — script with builder.reply*/defaultReply*");
    }

    /** Stable pseudo-vector seeded by the input text; same text → same vector, no network. */
    private float[] deterministicVector(String input) {
        Objects.requireNonNull(input, "embedding input");
        long seed = 1125899906842597L;
        for (int i = 0; i < input.length(); i++) {
            seed = 31 * seed + input.charAt(i);
        }
        Random rnd = new Random(seed);
        float[] vector = new float[embeddingDim];
        for (int i = 0; i < embeddingDim; i++) {
            vector[i] = (float) (rnd.nextDouble() * 2.0 - 1.0);
        }
        return vector;
    }

    /** Fluent builder for scripted responses. */
    public static final class Builder {
        private final Deque<ChatResult> chats = new ArrayDeque<>();
        private ChatResult defaultReply;
        private ModelInfo model = new ModelInfo("stub", "stub-model", true);
        private int embeddingDim = 384; // all-MiniLM-L6-v2 dimensionality

        public Builder model(ModelInfo model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        public Builder embeddingDim(int dim) {
            if (dim < 1) {
                throw new IllegalArgumentException("embeddingDim must be >= 1");
            }
            this.embeddingDim = dim;
            return this;
        }

        /** Enqueue a fully-specified chat result. */
        public Builder reply(ChatResult result) {
            chats.add(Objects.requireNonNull(result, "result"));
            return this;
        }

        /** Enqueue a text-only answer turn. */
        public Builder replyText(String text) {
            chats.add(textResult(Objects.requireNonNull(text, "text")));
            return this;
        }

        /** Enqueue a tool-calling turn (empty text, the given calls). */
        public Builder replyToolCalls(ToolCall... calls) {
            chats.add(new ChatResult("", List.of(calls), model, usageFor("")));
            return this;
        }

        /** Reply used when the scripted queue is exhausted (otherwise an exhausted stub throws). */
        public Builder defaultReplyText(String text) {
            this.defaultReply = textResult(Objects.requireNonNull(text, "text"));
            return this;
        }

        public StubLlmGateway build() {
            return new StubLlmGateway(this);
        }

        private ChatResult textResult(String text) {
            return new ChatResult(text, List.of(), model, usageFor(text));
        }

        private Usage usageFor(String text) {
            int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
            return new Usage(0, words, words);
        }
    }
}
