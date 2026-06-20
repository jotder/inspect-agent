package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.StubLlmGateway;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runner aggregation, per-case pass/fail, failure isolation, and the OFFLINE stub-gateway leg
 *  (T-008 AC2, AC3; eval-harness AC2, AC6). */
class DefaultEvalHarnessTest {

    private static final PageContext PAGE = new PageContext("home", Map.of(), Map.of());

    private static EvalSuite smokeSuite() {
        try (InputStream in = DefaultEvalHarnessTest.class
                .getResourceAsStream("/eval/phase1-smoke/smoke.yaml")) {
            return YamlEvalCaseLoader.load(in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AgentAnswer text(String t) {
        return new AgentAnswer(AnswerKind.TEXT, t, null, null, List.of(), new RunId("r"));
    }

    private static AgentAnswer nav(String target, Map<String, String> params) {
        return new AgentAnswer(AnswerKind.NAVIGATION, "", null,
                new NavigationIntent(target, params, "why"), List.of(), new RunId("r"));
    }

    @Test
    void runsTheWholeSuiteAndReportsAllPass() { // AC2
        Map<String, AgentAnswer> answers = Map.of(
                "say hello", text("hello there"),
                "echo ok", text("ok"),
                "why did the pipeline fail?",
                nav("pipeline-run-history", Map.of("pipelineId", "pl-123", "status", "FAILED", "period", "Q3")));

        EvalReport report = new DefaultEvalHarness()
                .run(smokeSuite(), new ScriptedAgentService(answers), DeploymentProfile.OFFLINE);

        assertThat(report.suite()).isEqualTo("phase1-smoke");
        assertThat(report.profile()).isEqualTo(DeploymentProfile.OFFLINE);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(report.outcomes()).hasSize(3).allMatch(o -> o.score().pass());
    }

    @Test
    void reportsPerCasePassAndFail() { // AC2
        Map<String, AgentAnswer> answers = Map.of(
                "say hello", text("hello there"),
                "echo ok", text("ok"),
                "why did the pipeline fail?", nav("WRONG-page", Map.of("pipelineId", "pl-123")));

        EvalReport report = new DefaultEvalHarness()
                .run(smokeSuite(), new ScriptedAgentService(answers), DeploymentProfile.OFFLINE);

        assertThat(report.passed()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(outcome(report, "nav-pipeline-failures").score().pass()).isFalse();
    }

    @Test
    void isolatesAThrowingCaseAndCompletesTheSuite() { // AC2 (failure isolation)
        // "echo ok" is absent → that case throws inside ask(); the suite must still finish.
        Map<String, AgentAnswer> answers = Map.of(
                "say hello", text("hello there"),
                "why did the pipeline fail?",
                nav("pipeline-run-history", Map.of("pipelineId", "pl-123", "status", "FAILED")));

        EvalReport report = new DefaultEvalHarness()
                .run(smokeSuite(), new ScriptedAgentService(answers), DeploymentProfile.OFFLINE);

        assertThat(report.total()).isEqualTo(3);
        CaseOutcome failed = outcome(report, "qa-exact-ok");
        assertThat(failed.score().pass()).isFalse();
        assertThat(failed.score().detail()).isNotBlank();
    }

    @Test
    void aThrownErrorCountsAsPassWhenTheCaseExpectsError() {
        EvalCase errorCase = new EvalCase("err", "boom", PAGE, Role.USER,
                new Expectation(AnswerKind.ERROR, null, List.of(), null, List.of()), Set.of());
        EvalSuite suite = new EvalSuite("err-suite", List.of(errorCase));

        EvalReport report = new DefaultEvalHarness()
                .run(suite, new AlwaysThrowingAgentService(), DeploymentProfile.OFFLINE);

        assertThat(report.outcomes().get(0).score().pass()).isTrue();
    }

    @Test
    void runsOfflineAgainstTheStubGateway() { // AC3 / AC6 — no network, no live model
        LlmGateway gateway = StubLlmGateway.builder().defaultReplyText("hello there").build();
        EvalCase c = new EvalCase("g", "say hello", PAGE, Role.USER,
                new Expectation(AnswerKind.TEXT, new AnswerAssertion(MatchMode.CONTAINS, "hello", 0.0),
                        List.of(), null, List.of()),
                Set.of());
        EvalSuite suite = new EvalSuite("gw", List.of(c));

        EvalReport report = new DefaultEvalHarness()
                .run(suite, new GatewayTextAgentService(gateway), DeploymentProfile.OFFLINE);

        assertThat(report.passed()).isEqualTo(1);
    }

    private static CaseOutcome outcome(EvalReport report, String caseId) {
        return report.outcomes().stream()
                .filter(o -> o.case_().id().equals(caseId))
                .findFirst().orElseThrow();
    }

    // --- test agents -------------------------------------------------------------------------

    /** Returns scripted answers keyed by prompt; a missing prompt makes the case throw. */
    private record ScriptedAgentService(Map<String, AgentAnswer> answers) implements AgentService {
        @Override
        public AgentSession open(SessionRequest req) {
            return new AgentSession() {
                @Override
                public AgentAnswer ask(UserMessage msg) {
                    AgentAnswer a = answers.get(msg.text());
                    if (a == null) {
                        throw new IllegalStateException("no scripted answer for: " + msg.text());
                    }
                    return a;
                }

                @Override
                public void askStream(UserMessage msg, AnswerSink sink) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class AlwaysThrowingAgentService implements AgentService {
        @Override
        public AgentSession open(SessionRequest req) {
            return new AgentSession() {
                @Override
                public AgentAnswer ask(UserMessage msg) {
                    throw new IllegalStateException("boom");
                }

                @Override
                public void askStream(UserMessage msg, AnswerSink sink) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Turns the stub gateway's chat text into a TEXT answer — proves an offline gateway-backed run. */
    private record GatewayTextAgentService(LlmGateway gateway) implements AgentService {
        @Override
        public AgentSession open(SessionRequest req) {
            return new AgentSession() {
                @Override
                public AgentAnswer ask(UserMessage msg) {
                    ChatResult r = gateway.chat(new ChatRequest(
                            List.of(new ChatMessageRecord(ChatRole.USER, msg.text(), Instant.EPOCH, Map.of())),
                            List.of(), ChatOptions.defaults()));
                    return new AgentAnswer(AnswerKind.TEXT, r.text(), null, null, List.of(), new RunId("run-1"));
                }

                @Override
                public void askStream(UserMessage msg, AnswerSink sink) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
