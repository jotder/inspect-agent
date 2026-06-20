package com.eoiagent.model;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.Feature;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.PolicyViolation;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The public {@link LlmGateway}: an ordered chain of chat backends (primary then fallbacks) plus an
 * embedding backend, routed per {@link com.eoiagent.core.DeploymentProfile}. A hosted backend (one
 * whose {@link ModelInfo#local()} is false) is consulted only when
 * {@code ConfigProvider.featureEnabled(HOSTED_MODELS)} is true; otherwise it is skipped <em>before</em>
 * any network call. If no backend is eligible the gateway throws {@link PolicyViolation} — OFFLINE
 * fails closed and never attempts egress (model-gateway spec; T-107).
 */
public final class RoutingLlmGateway implements LlmGateway, AutoCloseable {

    private final List<LlmGateway> chatChain;
    private final EmbeddingModel embeddingModel; // nullable
    private final ModelInfo embeddingModelInfo;  // nullable
    private final ConfigProvider config;
    private volatile ModelInfo active;

    private RoutingLlmGateway(Builder b) {
        this.chatChain = List.copyOf(b.chatChain);
        this.embeddingModel = b.embeddingModel;
        this.embeddingModelInfo = b.embeddingModelInfo;
        this.config = b.config;
        this.active = chatChain.isEmpty() ? null : chatChain.get(0).activeChatModel();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> attempts = new ArrayList<>();
        boolean anyEligible = false;
        for (LlmGateway delegate : chatChain) {
            ModelInfo info = delegate.activeChatModel();
            if (skipHosted(info, attempts)) {
                continue;
            }
            anyEligible = true;
            try {
                ChatResult result = delegate.chat(request);
                this.active = result.model() != null ? result.model() : info;
                return result;
            } catch (ModelUnavailableException e) {
                attempts.add(name(info) + " (failed: " + e.getMessage() + ")");
            }
        }
        throw noBackend(anyEligible, attempts);
    }

    @Override
    public void chatStream(ChatRequest request, TokenSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        List<String> attempts = new ArrayList<>();
        boolean anyEligible = false;
        for (LlmGateway delegate : chatChain) {
            ModelInfo info = delegate.activeChatModel();
            if (skipHosted(info, attempts)) {
                continue;
            }
            anyEligible = true;
            try {
                this.active = info;
                delegate.chatStream(request, sink); // mid-stream failures surface via sink.onError
                return;
            } catch (ModelUnavailableException e) {
                attempts.add(name(info) + " (failed before streaming: " + e.getMessage() + ")");
            }
        }
        throw noBackend(anyEligible, attempts);
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        Objects.requireNonNull(request, "request");
        if (embeddingModel == null) {
            throw new ModelUnavailableException("no embedding backend configured");
        }
        List<TextSegment> segments = request.inputs().stream().map(TextSegment::from).toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<float[]> vectors = embeddings.stream().map(Embedding::vector).toList();
        ModelInfo info = embeddingModelInfo != null
                ? embeddingModelInfo : new ModelInfo("embedding", "embedding", true);
        return new EmbeddingResult(vectors, info);
    }

    @Override
    public ModelInfo activeChatModel() {
        return active;
    }

    @Override
    public boolean isAvailable(ModelRole role) {
        if (role == ModelRole.EMBEDDING) {
            return embeddingModel != null;
        }
        for (LlmGateway delegate : chatChain) {
            ModelInfo info = delegate.activeChatModel();
            boolean hosted = info != null && !info.local();
            if (hosted && !config.featureEnabled(Feature.HOSTED_MODELS)) {
                continue;
            }
            if (delegate.isAvailable(ModelRole.CHAT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (LlmGateway delegate : chatChain) {
            closeQuietly(delegate);
        }
        closeQuietly(embeddingModel);
    }

    /** Returns true (and records it) if {@code info} is a hosted backend disabled by the profile. */
    private boolean skipHosted(ModelInfo info, List<String> attempts) {
        boolean hosted = info != null && !info.local();
        if (hosted && !config.featureEnabled(Feature.HOSTED_MODELS)) {
            attempts.add(name(info) + " (skipped: HOSTED_MODELS disabled for " + config.profile() + ")");
            return true;
        }
        return false;
    }

    private RuntimeException noBackend(boolean anyEligible, List<String> attempts) {
        if (!anyEligible) {
            return new PolicyViolation("no eligible chat backend for profile " + config.profile()
                    + " — hosted models are disabled; attempted " + attempts);
        }
        return new ModelUnavailableException("all chat backends failed; attempted " + attempts);
    }

    private static String name(ModelInfo info) {
        return info == null ? "unknown" : info.provider() + "/" + info.modelId();
    }

    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort close
            }
        }
    }

    /** Builds a router; chat backends are tried in the order added (primary first). */
    public static final class Builder {
        private final List<LlmGateway> chatChain = new ArrayList<>();
        private EmbeddingModel embeddingModel;
        private ModelInfo embeddingModelInfo;
        private ConfigProvider config;

        public Builder config(ConfigProvider config) {
            this.config = config;
            return this;
        }

        public Builder addChatBackend(LlmGateway backend) {
            chatChain.add(Objects.requireNonNull(backend, "backend"));
            return this;
        }

        public Builder embedding(EmbeddingModel model, ModelInfo info) {
            this.embeddingModel = model;
            this.embeddingModelInfo = info;
            return this;
        }

        public RoutingLlmGateway build() {
            Objects.requireNonNull(config, "config");
            return new RoutingLlmGateway(this);
        }
    }
}
