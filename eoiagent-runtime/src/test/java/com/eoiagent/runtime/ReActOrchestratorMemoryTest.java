package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.memory.MemoryStore;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-351: session memory in the ReAct loop — prior turns seed the model context, the new turn is
 * persisted, the context window is bounded, tool chatter stays out of the transcript, and a
 * memory-less orchestrator behaves exactly as before.
 */
class ReActOrchestratorMemoryTest {

    /** Map-backed store fake — runtime tests keep zero adapter-module deps (ports only). */
    private static final class FakeMemoryStore implements MemoryStore {
        final Map<String, List<ChatMessageRecord>> data = new HashMap<>();

        @Override
        public void put(SessionId id, List<ChatMessageRecord> messages) {
            data.put(id.value(), List.copyOf(messages));
        }

        @Override
        public List<ChatMessageRecord> get(SessionId id) {
            return data.getOrDefault(id.value(), List.of());
        }

        @Override
        public void delete(SessionId id) {
            data.remove(id.value());
        }
    }

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s-1"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ChatMessageRecord turn(ChatRole role, String text) {
        return new ChatMessageRecord(role, text, Instant.now(), Map.of());
    }

    private static ReActOrchestrator orchestrator(ScriptedGateway gateway, RecordingAuditSink sink,
                                                  MemoryStore memory) {
        DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        registry.register(new FixedTool("echo", "ok"));
        return new ReActOrchestrator(gateway, registry, new InMemoryScratchpad(),
                sink, new FakeRuntimeConfig(12, 8192), new RecordingTraceCollector(), memory);
    }

    @Test
    void priorTurnsSeedTheModelContextBeforeTheNewGoal() {
        FakeMemoryStore store = new FakeMemoryStore();
        store.put(new SessionId("s-1"), List.of(
                turn(ChatRole.USER, "what is orders_daily?"),
                turn(ChatRole.ASSISTANT, "orders_daily is the nightly revenue pipeline")));
        ScriptedGateway gateway = new ScriptedGateway().finalText("it last failed on 2026-06-20");

        orchestrator(gateway, new RecordingAuditSink(), store).run(
                new Goal("when did IT last fail?", GoalKind.QA), ctx());

        assertThat(gateway.lastMessages).hasSize(3);
        assertThat(gateway.lastMessages.get(0).text()).isEqualTo("what is orders_daily?");
        assertThat(gateway.lastMessages.get(1).role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(gateway.lastMessages.get(2).text()).isEqualTo("when did IT last fail?");
    }

    @Test
    void newTurnsArePersistedSoTheTranscriptGrowsAcrossRuns() {
        FakeMemoryStore store = new FakeMemoryStore();
        ScriptedGateway gateway = new ScriptedGateway()
                .finalText("first answer").finalText("second answer");
        ReActOrchestrator orch = orchestrator(gateway, new RecordingAuditSink(), store);

        orch.run(new Goal("q1", GoalKind.QA), ctx());
        orch.run(new Goal("q2", GoalKind.QA), ctx());

        List<ChatMessageRecord> transcript = store.get(new SessionId("s-1"));
        assertThat(transcript).extracting(ChatMessageRecord::text)
                .containsExactly("q1", "first answer", "q2", "second answer");
        assertThat(transcript).extracting(ChatMessageRecord::role)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT);
    }

    @Test
    void contextWindowIsBoundedButTheStoredTranscriptKeepsEverything() {
        FakeMemoryStore store = new FakeMemoryStore();
        List<ChatMessageRecord> longTranscript = new ArrayList<>();
        for (int i = 1; i <= 25; i++) { // over the default 20-message window
            longTranscript.add(turn(i % 2 == 1 ? ChatRole.USER : ChatRole.ASSISTANT, "turn-" + i));
        }
        store.put(new SessionId("s-1"), longTranscript);
        ScriptedGateway gateway = new ScriptedGateway().finalText("done");

        orchestrator(gateway, new RecordingAuditSink(), store).run(
                new Goal("latest question", GoalKind.QA), ctx());

        assertThat(gateway.lastMessages).hasSize(21); // 20 window + the new goal
        assertThat(gateway.lastMessages.get(0).text()).isEqualTo("turn-6"); // oldest 5 dropped
        assertThat(store.get(new SessionId("s-1"))).hasSize(27); // 25 + this run's 2 turns
    }

    @Test
    void toolTurnsStayRunScopedAndNeverEnterTheTranscript() {
        FakeMemoryStore store = new FakeMemoryStore();
        ScriptedGateway gateway = new ScriptedGateway().toolCall("echo").finalText("answer after tool");

        orchestrator(gateway, new RecordingAuditSink(), store).run(
                new Goal("use the tool", GoalKind.QA), ctx());

        assertThat(store.get(new SessionId("s-1"))).extracting(ChatMessageRecord::role)
                .containsExactly(ChatRole.USER, ChatRole.ASSISTANT); // no TOOL / tool-call turns
        assertThat(store.get(new SessionId("s-1")).get(1).text()).isEqualTo("answer after tool");
    }

    @Test
    void failedRunsPersistNothing() {
        FakeMemoryStore store = new FakeMemoryStore();
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new RuntimeException("boom"));

        AgentRun run = orchestrator(gateway, new RecordingAuditSink(), store).run(
                new Goal("q", GoalKind.QA), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.ERROR);
        assertThat(store.data).isEmpty();
    }

    @Test
    void withoutAMemoryStoreBehaviorIsUnchanged() {
        ScriptedGateway gateway = new ScriptedGateway().finalText("answer");

        AgentRun run = orchestrator(gateway, new RecordingAuditSink(), null).run(
                new Goal("q", GoalKind.QA), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(gateway.lastMessages).hasSize(1); // just the goal, as before T-351
    }
}
