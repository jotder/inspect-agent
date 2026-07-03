package com.eoiagent.model;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolCallMeta;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-350: tool calls map across the LC4j seam in both directions, and tool-call turns round-trip
 * through history via the {@link ToolCallMeta} convention — the piece that makes real models able
 * to drive the agent loop. Fake LC4j models only; fully offline.
 */
class Lc4jChatGatewayToolCallTest {

    private static final ModelInfo INFO = new ModelInfo("fake", "fake-1", true);

    /** Captures the LC4j request and replies with a scripted response. */
    private static final class CapturingModel implements ChatModel {
        dev.langchain4j.model.chat.request.ChatRequest lastRequest;
        private final ChatResponse response;

        CapturingModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
            this.lastRequest = request;
            return response;
        }
    }

    private static ChatRequest requestWithTool() {
        ToolSpec spec = new ToolSpec("list_pipelines", "Lists pipelines",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}},\"required\":[\"status\"]}",
                false, Role.USER, Capability.READ_METADATA);
        return new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.USER, "what failed?", Instant.now(), Map.of())),
                List.of(spec), ChatOptions.defaults());
    }

    @Test
    void sendsToolSpecificationsWithParsedSchema() {
        CapturingModel model = new CapturingModel(ChatResponse.builder()
                .aiMessage(AiMessage.from("no tools needed")).build());
        Lc4jChatGateway gateway = new Lc4jChatGateway(model, null, INFO);

        gateway.chat(requestWithTool());

        var specs = model.lastRequest.toolSpecifications();
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("list_pipelines");
        assertThat(specs.get(0).parameters()).isNotNull();
        assertThat(specs.get(0).parameters().properties()).containsKey("status");
        assertThat(specs.get(0).parameters().required()).containsExactly("status");
    }

    @Test
    void mapsModelToolRequestsToToolCallsWithIdAndParsedArguments() {
        AiMessage withCalls = AiMessage.from(ToolExecutionRequest.builder()
                .id("call-7").name("list_pipelines").arguments("{\"status\":\"FAILED\",\"limit\":3}").build());
        CapturingModel model = new CapturingModel(ChatResponse.builder().aiMessage(withCalls).build());
        Lc4jChatGateway gateway = new Lc4jChatGateway(model, null, INFO);

        ChatResult result = gateway.chat(requestWithTool());

        assertThat(result.toolCalls()).hasSize(1);
        ToolCall call = result.toolCalls().get(0);
        assertThat(call.callId()).isEqualTo("call-7");
        assertThat(call.toolName()).isEqualTo("list_pipelines");
        assertThat(call.arguments()).containsEntry("status", "FAILED");
        assertThat(((Number) call.arguments().get("limit")).intValue()).isEqualTo(3);
    }

    @Test
    void malformedModelArgumentsSurviveUnderRawKeyInsteadOfCrashing() {
        AiMessage withCalls = AiMessage.from(ToolExecutionRequest.builder()
                .id("call-8").name("list_pipelines").arguments("{not json").build());
        CapturingModel model = new CapturingModel(ChatResponse.builder().aiMessage(withCalls).build());
        Lc4jChatGateway gateway = new Lc4jChatGateway(model, null, INFO);

        ChatResult result = gateway.chat(requestWithTool());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).arguments()).containsEntry(ToolMapping.RAW_ARGUMENTS_KEY, "{not json");
    }

    @Test
    void toolTurnHistoryRoundTripsAsPairedLc4jMessages() {
        // History as the ReAct loop now writes it: assistant tool-call turn + paired TOOL result.
        ToolCall call = new ToolCall("call-9", "list_pipelines", Map.of("status", "FAILED"), null);
        List<ChatMessageRecord> history = List.of(
                new ChatMessageRecord(ChatRole.USER, "what failed?", Instant.now(), Map.of()),
                new ChatMessageRecord(ChatRole.ASSISTANT, "", Instant.now(), ToolCallMeta.encode(List.of(call))),
                new ChatMessageRecord(ChatRole.TOOL, "orders_daily failed", Instant.now(), ToolCallMeta.forResult(call)));
        CapturingModel model = new CapturingModel(ChatResponse.builder()
                .aiMessage(AiMessage.from("orders_daily failed at step 3")).build());
        Lc4jChatGateway gateway = new Lc4jChatGateway(model, null, INFO);

        gateway.chat(new ChatRequest(history, List.of(), ChatOptions.defaults()));

        List<ChatMessage> sent = model.lastRequest.messages();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1)).isInstanceOfSatisfying(AiMessage.class, ai -> {
            assertThat(ai.hasToolExecutionRequests()).isTrue();
            assertThat(ai.toolExecutionRequests().get(0).id()).isEqualTo("call-9");
            assertThat(ai.toolExecutionRequests().get(0).name()).isEqualTo("list_pipelines");
            assertThat(ai.toolExecutionRequests().get(0).arguments()).contains("FAILED");
        });
        assertThat(sent.get(2)).isInstanceOfSatisfying(ToolExecutionResultMessage.class, tool -> {
            assertThat(tool.id()).isEqualTo("call-9");
            assertThat(tool.toolName()).isEqualTo("list_pipelines");
            assertThat(tool.text()).isEqualTo("orders_daily failed");
        });
    }

    @Test
    void legacyRecordsWithoutMetaKeepTheirOldMapping() {
        List<ChatMessageRecord> history = List.of(
                new ChatMessageRecord(ChatRole.ASSISTANT, "plain answer", Instant.now(), Map.of()),
                new ChatMessageRecord(ChatRole.TOOL, "raw tool text", Instant.now(), Map.of()));
        CapturingModel model = new CapturingModel(ChatResponse.builder()
                .aiMessage(AiMessage.from("ok")).build());
        Lc4jChatGateway gateway = new Lc4jChatGateway(model, null, INFO);

        gateway.chat(new ChatRequest(history, List.of(), ChatOptions.defaults()));

        List<ChatMessage> sent = model.lastRequest.messages();
        assertThat(sent.get(0)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) sent.get(0)).hasToolExecutionRequests()).isFalse();
        assertThat(sent.get(1)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
    }
}
