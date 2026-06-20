package com.eoiagent.model;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Route + fallback per profile, with OFFLINE failing closed (T-107 AC1–AC3). */
class RoutingLlmGatewayTest {

    private static final ModelInfo LOCAL = new ModelInfo("ollama", "local-m", true);
    private static final ModelInfo HOSTED = new ModelInfo("anthropic", "claude", false);

    private static ChatRequest req() {
        return new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.USER, "hi", Instant.EPOCH, Map.of())),
                List.of(), ChatOptions.defaults());
    }

    private static LlmGateway answering(ModelInfo info, String reply) {
        return StubLlmGateway.builder().model(info).defaultReplyText(reply).build();
    }

    private static LlmGateway silent(ModelInfo info) {
        return StubLlmGateway.builder().model(info).build(); // throws if actually invoked
    }

    @Test
    void cloudFallsBackFromFailingHostedToLocal() { // AC1
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.CLOUD, true))
                .addChatBackend(new FailingGateway(HOSTED))
                .addChatBackend(answering(LOCAL, "local-answer"))
                .build();

        ChatResult result = gw.chat(req());

        assertThat(result.text()).isEqualTo("local-answer");
        assertThat(gw.activeChatModel().local()).isTrue();
    }

    @Test
    void offlineWithOnlyHostedFailsClosed() { // AC2
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.OFFLINE, false))
                .addChatBackend(silent(HOSTED)) // would throw IllegalStateException if invoked
                .build();

        assertThatThrownBy(() -> gw.chat(req()))
                .isInstanceOf(PolicyViolation.class); // skipped before any call → never egresses
    }

    @Test
    void offlineSkipsHostedAndUsesLocal() { // AC2
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.OFFLINE, false))
                .addChatBackend(silent(HOSTED))
                .addChatBackend(answering(LOCAL, "local-answer"))
                .build();

        assertThat(gw.chat(req()).text()).isEqualTo("local-answer");
    }

    @Test
    void activeChatModelReflectsTheHostedBackendThatAnswered() { // AC3
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.CLOUD, true))
                .addChatBackend(answering(HOSTED, "hosted-answer"))
                .addChatBackend(answering(LOCAL, "local-answer"))
                .build();

        ChatResult result = gw.chat(req());

        assertThat(result.text()).isEqualTo("hosted-answer");
        assertThat(gw.activeChatModel().provider()).isEqualTo("anthropic");
        assertThat(gw.activeChatModel().local()).isFalse();
    }

    @Test
    void allBackendsFailingThrowsModelUnavailable() {
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.CLOUD, true))
                .addChatBackend(new FailingGateway(LOCAL))
                .addChatBackend(new FailingGateway(new ModelInfo("ollama", "local-2", true)))
                .build();

        assertThatThrownBy(() -> gw.chat(req())).isInstanceOf(ModelUnavailableException.class);
    }

    @Test
    void embedDelegatesToTheEmbeddingBackend() {
        RoutingLlmGateway gw = RoutingLlmGateway.builder()
                .config(new FakeConfig(DeploymentProfile.OFFLINE, false))
                .addChatBackend(answering(LOCAL, "x"))
                .embedding(new FixedEmbedding(), new ModelInfo("onnx-all-minilm", "minilm", true))
                .build();

        EmbeddingResult result = gw.embed(new EmbeddingRequest(List.of("a", "b")));

        assertThat(result.vectors()).hasSize(2);
        assertThat(result.model().local()).isTrue();
    }

    // --- test doubles ------------------------------------------------------------------------

    private record FakeConfig(DeploymentProfile profile, boolean hostedEnabled) implements ConfigProvider {
        @Override
        public <T> T get(ConfigKey<T> key) {
            return key.defaultValue();
        }

        @Override
        public boolean featureEnabled(Feature feature) {
            return feature == Feature.HOSTED_MODELS ? hostedEnabled : true;
        }
    }

    private record FailingGateway(ModelInfo info) implements LlmGateway {
        @Override
        public ChatResult chat(ChatRequest request) {
            throw new ModelUnavailableException("backend down");
        }

        @Override
        public void chatStream(ChatRequest request, TokenSink sink) {
            throw new ModelUnavailableException("backend down");
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelInfo activeChatModel() {
            return info;
        }

        @Override
        public boolean isAvailable(ModelRole role) {
            return false;
        }
    }

    private static final class FixedEmbedding implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> out = new ArrayList<>();
            for (TextSegment ignored : segments) {
                out.add(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}));
            }
            return Response.from(out);
        }
    }
}
