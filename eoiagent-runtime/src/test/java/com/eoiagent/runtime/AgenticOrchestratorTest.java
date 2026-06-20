package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgenticOrchestrator builds a real langchain4j-agentic agent over a fake (offline) ChatModel and
 * maps its output to an AgentAnswer (T-111; AC3 establishes the quarantined dependency).
 */
class AgenticOrchestratorTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    /** A fake LC4j ChatModel — no network; returns a fixed answer for any request. */
    private static ChatModel fixedModel(String reply) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
    }

    @Test
    void runsAgenticQaToTextAnswer() {
        RecordingAuditSink sink = new RecordingAuditSink();
        AgenticOrchestrator orch = new AgenticOrchestrator(fixedModel("agentic says: all clear"), sink);

        AgentRun run = orch.run(new Goal("status?", GoalKind.QA), ctx());

        assertThat(run.answer().kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(run.answer().text()).contains("agentic says");
        assertThat(sink.kinds()).containsExactly(AuditKind.MODEL_CALL, AuditKind.DECISION);
    }
}
