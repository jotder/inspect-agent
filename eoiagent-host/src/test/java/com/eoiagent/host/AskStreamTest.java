package com.eoiagent.host;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.runtime.AgentRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** askStream delivers tokens then a terminal answer, and routes faults to onError (T-114 AC3; spec AC7). */
class AskStreamTest {

    private static AgentSession session(StubOrchestrator orch) {
        DefaultAgentService svc = new DefaultAgentService(new AppId("app"),
                new FakeConfig(DeploymentProfile.OFFLINE), orch, StubGuardrail.pass(), new RecordingAuditSink());
        return svc.open(new SessionRequest(new UserId("u"), Role.USER, DeploymentProfile.OFFLINE,
                new PageContext("dashboard", Map.of(), Map.of()), Map.of()));
    }

    private static UserMessage msg(String text) {
        return new UserMessage(text, new PageContext("dashboard", Map.of(), Map.of()), Instant.EPOCH);
    }

    @Test
    void deliversTokensThenExactlyOneComplete() { // AC3
        AgentAnswer text = new AgentAnswer(AnswerKind.TEXT, "pipeline 42 failed", null, null, List.of(), new RunId("r"));
        AgentSession s = session(new StubOrchestrator()
                .returning(new AgentRun(new RunId("r"), text, new TaskList(List.of()), List.of(), 1)));
        CollectingAnswerSink sink = new CollectingAnswerSink();

        s.askStream(msg("why?"), sink);

        assertThat(sink.tokens).containsExactly("pipeline", "42", "failed");
        assertThat(sink.completeCalls).isEqualTo(1);
        assertThat(sink.completed.kind()).isEqualTo(AnswerKind.TEXT);
        assertThat(sink.errorCalls).isZero();
    }

    @Test
    void streamFaultYieldsOnErrorAndNoComplete() { // spec AC7
        AgentSession s = session(new StubOrchestrator().failing(new RuntimeException("stream boom")));
        CollectingAnswerSink sink = new CollectingAnswerSink();

        s.askStream(msg("why?"), sink);

        assertThat(sink.errorCalls).isEqualTo(1);
        assertThat(sink.completeCalls).isZero();
        assertThat(sink.tokens).isEmpty();
    }
}
