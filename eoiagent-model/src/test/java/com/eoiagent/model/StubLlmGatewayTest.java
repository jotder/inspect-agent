package com.eoiagent.model;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic, offline behavior of the scripted stub gateway (T-007 AC1–AC3). */
class StubLlmGatewayTest {

    private static ChatRequest req(String text) {
        return new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.USER, text, Instant.EPOCH, Map.of())),
                List.of(),
                ChatOptions.defaults());
    }

    @Test
    void chatReturnsScriptedResult() { // AC1
        StubLlmGateway gateway = StubLlmGateway.builder().replyText("hello world").build();

        ChatResult result = gateway.chat(req("hi"));

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.model().local()).isTrue();
        assertThat(gateway.activeChatModel()).isEqualTo(result.model());
    }

    @Test
    void embedReturnsDeterministic384DimVectorsInOrder() { // AC1
        StubLlmGateway gateway = StubLlmGateway.builder().build();

        EmbeddingResult result = gateway.embed(new EmbeddingRequest(List.of("alpha", "beta")));

        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).hasSize(384);
        assertThat(result.model().local()).isTrue();

        // same input → identical vector (deterministic); different inputs differ
        float[] alphaAgain = gateway.embed(new EmbeddingRequest(List.of("alpha"))).vectors().get(0);
        assertThat(alphaAgain).isEqualTo(result.vectors().get(0));
        assertThat(result.vectors().get(0)).isNotEqualTo(result.vectors().get(1));
    }

    @Test
    void scriptsAToolCallTurnThenAFinalAnswer() { // AC2
        ToolCall call = new ToolCall("list_runs", Map.of("pipelineId", "pl-1"), new RunId("r1"));
        StubLlmGateway gateway = StubLlmGateway.builder()
                .replyToolCalls(call)
                .replyText("the pipeline failed at step 3")
                .build();

        ChatResult first = gateway.chat(req("why did it fail?"));
        assertThat(first.toolCalls()).containsExactly(call);
        assertThat(first.text()).isEmpty();

        ChatResult second = gateway.chat(req("continue"));
        assertThat(second.toolCalls()).isEmpty();
        assertThat(second.text()).isEqualTo("the pipeline failed at step 3");
    }

    @Test
    void chatStreamDeliversTokensInOrderThenExactlyOneComplete() {
        StubLlmGateway gateway = StubLlmGateway.builder().replyText("a b c").build();
        RecordingSink sink = new RecordingSink();

        gateway.chatStream(req("x"), sink);

        assertThat(sink.tokens).containsExactly("a", "b", "c");
        assertThat(sink.completeCount).isEqualTo(1);
        assertThat(sink.completed.text()).isEqualTo("a b c");
        assertThat(sink.error).isNull();
    }

    @Test
    void worksFullyOfflineWithNoConfigOrNetwork() { // AC3
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText("ok").build();

        assertThat(gateway.isAvailable(ModelRole.CHAT)).isTrue();
        assertThat(gateway.isAvailable(ModelRole.EMBEDDING)).isTrue();
        assertThat(gateway.chat(req("hi")).text()).isEqualTo("ok");
        assertThat(gateway.embed(new EmbeddingRequest(List.of("z"))).vectors().get(0)).hasSize(384);
    }

    @Test
    void exhaustedScriptWithoutDefaultThrows() {
        StubLlmGateway gateway = StubLlmGateway.builder().build();
        assertThatThrownBy(() -> gateway.chat(req("hi"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultReplyIsReusedWhenScriptExhausted() {
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText("fallback").build();
        assertThat(gateway.chat(req("a")).text()).isEqualTo("fallback");
        assertThat(gateway.chat(req("b")).text()).isEqualTo("fallback");
    }

    @Test
    void embedRejectsEmptyInputs() {
        StubLlmGateway gateway = StubLlmGateway.builder().build();
        assertThatThrownBy(() -> gateway.embed(new EmbeddingRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddingDimensionIsConfigurable() {
        StubLlmGateway gateway = StubLlmGateway.builder().embeddingDim(8).build();
        assertThat(gateway.embed(new EmbeddingRequest(List.of("x"))).vectors().get(0)).hasSize(8);
    }

    /** Captures streamed tokens and the terminal signal. */
    private static final class RecordingSink implements TokenSink {
        final List<String> tokens = new ArrayList<>();
        ChatResult completed;
        Throwable error;
        int completeCount;

        @Override
        public void onToken(String token) {
            tokens.add(token);
        }

        @Override
        public void onComplete(ChatResult result) {
            this.completed = result;
            this.completeCount++;
        }

        @Override
        public void onError(Throwable e) {
            this.error = e;
        }
    }
}
