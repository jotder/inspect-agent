package com.eoiagent.model;

import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maps our ChatRequest/ChatResult to/from LangChain4j against injected fake models — no network. */
class Lc4jChatGatewayTest {

    private static ChatRequest req(String text) {
        return new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.USER, text, Instant.EPOCH, Map.of())),
                List.of(), ChatOptions.defaults());
    }

    private static ChatModel fakeChat(String reply) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
    }

    @Test
    void chatMapsAiMessageTextToChatResult() {
        Lc4jChatGateway gw = new Lc4jChatGateway(fakeChat("the answer"), null, new ModelInfo("test", "m", true));

        ChatResult result = gw.chat(req("hi"));

        assertThat(result.text()).isEqualTo("the answer");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.model().local()).isTrue();
        assertThat(gw.activeChatModel().provider()).isEqualTo("test");
    }

    @Test
    void chatStreamForwardsTokensThenComplete() {
        StreamingChatModel fakeStream = new StreamingChatModel() {
            @Override
            public void chat(dev.langchain4j.model.chat.request.ChatRequest request, StreamingChatResponseHandler h) {
                h.onPartialResponse("hel");
                h.onPartialResponse("lo");
                h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("hello")).build());
            }
        };
        Lc4jChatGateway gw = new Lc4jChatGateway(fakeChat("x"), fakeStream, new ModelInfo("test", "m", true));

        List<String> tokens = new ArrayList<>();
        ChatResult[] completed = new ChatResult[1];
        gw.chatStream(req("hi"), new TokenSink() {
            @Override public void onToken(String t) { tokens.add(t); }
            @Override public void onComplete(ChatResult r) { completed[0] = r; }
            @Override public void onError(Throwable e) { throw new AssertionError(e); }
        });

        assertThat(tokens).containsExactly("hel", "lo");
        assertThat(completed[0].text()).isEqualTo("hello");
    }

    @Test
    void chatStreamUnsupportedWithoutStreamingModel() {
        Lc4jChatGateway gw = new Lc4jChatGateway(fakeChat("x"), null, new ModelInfo("test", "m", true));
        assertThatThrownBy(() -> gw.chatStream(req("hi"), new TokenSink() {
            @Override public void onToken(String t) { }
            @Override public void onComplete(ChatResult r) { }
            @Override public void onError(Throwable e) { }
        })).isInstanceOf(com.eoiagent.core.ModelUnavailableException.class);
    }

    @Test
    void embedIsUnsupportedOnAChatBackend() {
        Lc4jChatGateway gw = new Lc4jChatGateway(fakeChat("x"), null, new ModelInfo("test", "m", true));
        assertThatThrownBy(() -> gw.embed(new EmbeddingRequest(List.of("a"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
