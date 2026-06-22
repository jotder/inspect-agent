package com.eoiagent.memory;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SummarizingChatMemory condenses evicted turns into a running summary via a stub LlmGateway, and
 * falls back to plain window eviction (with an ERROR audit) when the model is unavailable (T-207 AC7).
 */
class SummarizingChatMemoryTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s1"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static List<String> texts(List<ChatMessage> messages) {
        return ChatMessageMapper.toRecords(messages).stream().map(ChatMessageRecord::text).toList();
    }

    @Test
    void summarizesEvictedTurnsIntoARetainedSummary() { // AC7 (summarize + retain)
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedSummaryGateway gateway = new ScriptedSummaryGateway("user greeted, then asked about orders");
        SummarizingChatMemory memory = new SummarizingChatMemory(ctx(), 2, gateway, store, sink);

        memory.add(UserMessage.from("hello"));
        memory.add(AiMessage.from("hi there"));
        memory.add(UserMessage.from("what about orders?")); // evicts "hello" → folded into summary

        List<ChatMessage> msgs = memory.messages();
        // The running summary is a leading system message carrying the model's summary text.
        assertThat(texts(msgs).get(0))
                .contains(SummarizingChatMemory.SUMMARY_PREFIX)
                .contains("asked about orders");
        // Recent window retained; the oldest raw turn is gone (condensed, not kept verbatim).
        assertThat(texts(msgs)).contains("hi there", "what about orders?");
        assertThat(texts(msgs)).doesNotContain("hello");
        // The summary call was made once and audited as a model call.
        assertThat(gateway.chatCalls).isEqualTo(1);
        assertThat(sink.kinds()).contains(AuditKind.MODEL_CALL);
    }

    @Test
    void fallsBackToWindowEvictionWhenModelUnavailable() { // AC7 (fallback + ERROR)
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedSummaryGateway gateway = new ScriptedSummaryGateway(new ModelUnavailableException("offline"));
        SummarizingChatMemory memory = new SummarizingChatMemory(ctx(), 2, gateway, store, sink);

        memory.add(UserMessage.from("m1"));
        memory.add(AiMessage.from("m2"));
        assertThatCode(() -> memory.add(UserMessage.from("m3"))).doesNotThrowAnyException();

        // No summary produced; the evicted turn was simply dropped (plain window eviction).
        assertThat(texts(memory.messages())).containsExactly("m2", "m3");
        assertThat(sink.kinds()).contains(AuditKind.ERROR);
    }

    @Test
    void rehydratesRunningSummaryFromTheStore() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        RecordingAuditSink sink = new RecordingAuditSink();
        ScriptedSummaryGateway gateway = new ScriptedSummaryGateway("summary so far");

        SummarizingChatMemory primed = new SummarizingChatMemory(ctx(), 1, gateway, store, sink);
        primed.add(UserMessage.from("first"));
        primed.add(AiMessage.from("second")); // evicts "first" → folded into the persisted summary

        // A fresh memory over the same store rehydrates the running summary, not as a raw turn.
        SummarizingChatMemory reloaded = new SummarizingChatMemory(ctx(), 1, gateway, store, sink);
        assertThat(texts(reloaded.messages()).get(0)).contains(SummarizingChatMemory.SUMMARY_PREFIX);
        assertThat(texts(reloaded.messages())).contains("second");
    }
}
