package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.TokenSink;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-355: the ReAct loop streams real tokens per model turn (tool turns stream nothing — their
 * scripted text is empty), and a backend that cannot stream degrades to one whole-text chunk
 * instead of failing the run.
 */
class ReActOrchestratorStreamingTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ReActOrchestrator orchestrator(ScriptedGateway gateway) {
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        registry.register(new FixedTool("echo", "ok"));
        return ReActOrchestrator.builder()
                .gateway(gateway).tools(registry).scratchpad(new InMemoryScratchpad())
                .audit(sink).config(new FakeRuntimeConfig(12, 8192))
                .build();
    }

    @Test
    void streamsFinalAnswerTokensLiveAcrossAToolTurn() {
        ScriptedGateway gateway = new ScriptedGateway()
                .toolCall("echo")                       // tool turn: empty text, no tokens
                .finalText("the pipeline failed at step 3");
        List<String> tokens = new ArrayList<>();

        AgentRun run = orchestrator(gateway).run(
                new Goal("what failed?", GoalKind.QA), ctx(), tokens::add);

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(tokens).containsExactly("the", "pipeline", "failed", "at", "step", "3");
    }

    @Test
    void nonStreamingBackendDegradesToOneChunkNotAFailure() {
        ScriptedGateway inner = new ScriptedGateway().finalText("whole answer at once");
        // Wrapper that refuses to stream, like a backend without a StreamingChatModel.
        var gateway = new com.eoiagent.model.LlmGateway() {
            @Override
            public ChatResult chat(ChatRequest request) {
                return inner.chat(request);
            }

            @Override
            public void chatStream(ChatRequest request, TokenSink sink) {
                throw new ModelUnavailableException("streaming not supported");
            }

            @Override
            public com.eoiagent.model.EmbeddingResult embed(com.eoiagent.model.EmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.eoiagent.model.ModelInfo activeChatModel() {
                return inner.activeChatModel();
            }

            @Override
            public boolean isAvailable(com.eoiagent.model.ModelRole role) {
                return true;
            }
        };
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        ReActOrchestrator orch = ReActOrchestrator.builder()
                .gateway(gateway).tools(registry).scratchpad(new InMemoryScratchpad())
                .audit(sink).config(new FakeRuntimeConfig(12, 8192))
                .build();
        List<String> tokens = new ArrayList<>();

        AgentRun run = orch.run(new Goal("q", GoalKind.QA), ctx(), tokens::add);

        assertThat(run.answer().text()).isEqualTo("whole answer at once");
        assertThat(tokens).containsExactly("whole answer at once"); // one chunk, degraded not broken
    }

    @Test
    void nullListenerBehavesExactlyLikeThePlainRun() {
        ScriptedGateway gateway = new ScriptedGateway().finalText("plain");

        AgentRun run = orchestrator(gateway).run(new Goal("q", GoalKind.QA), ctx(), null);

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).isEqualTo("plain");
    }
}
