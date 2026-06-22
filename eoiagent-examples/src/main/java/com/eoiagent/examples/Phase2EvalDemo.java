package com.eoiagent.examples;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.RunId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.eval.CaseOutcome;
import com.eoiagent.eval.CompositeScorer;
import com.eoiagent.eval.DefaultEvalHarness;
import com.eoiagent.eval.EvalReport;
import com.eoiagent.eval.EvalSuite;
import com.eoiagent.eval.YamlEvalCaseLoader;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.host.SessionRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase-2 — the eval harness (T-211). {@link DefaultEvalHarness} runs a YAML golden suite against an
 * {@link AgentService} and scores each case (answer kind + text match via {@link CompositeScorer}),
 * producing an {@link EvalReport}. This is the regression gate that keeps the agent honest as it
 * evolves.
 *
 * <p>Offline-deterministic: a scripted agent answers a small inline suite. One case is intentionally
 * answered "wrong" so the report shows a {@code FAIL} — exactly what would flag a regression in CI.
 */
public final class Phase2EvalDemo {

    private static final String SUITE = """
            suite: phase2-demo
            cases:
              - id: greeting
                prompt: "hello"
                role: USER
                expect:
                  expectedKind: TEXT
                  answer: { mode: CONTAINS, expected: "welcome" }
              - id: pipeline-status
                prompt: "What is the status of pipeline pl-1?"
                role: ANALYST
                expect:
                  expectedKind: TEXT
                  answer: { mode: CONTAINS, expected: "running" }
              - id: version-regression
                prompt: "What version is the platform?"
                role: USER
                expect:
                  expectedKind: TEXT
                  answer: { mode: CONTAINS, expected: "9.9" }
            """;

    /** Exact-prompt → scripted answer. The version answer deliberately won't satisfy the expectation. */
    private static final Map<String, String> SCRIPT = Map.of(
            "hello", "Hi there — welcome to the Acme Lakehouse Suite.",
            "What is the status of pipeline pl-1?", "Pipeline pl-1 is currently running.",
            "What version is the platform?", "The platform is at version 0.1.0.");

    private Phase2EvalDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Eval harness: scoring an agent against a Phase-2 golden suite");

        EvalSuite suite = YamlEvalCaseLoader.loadString(SUITE);
        EvalReport report = new DefaultEvalHarness(new CompositeScorer())
                .run(suite, new ScriptedAgentService(), DeploymentProfile.OFFLINE);

        DemoSupport.kv("suite", report.suite());
        DemoSupport.kv("result", report.passed() + "/" + report.total() + " passed, " + report.failed() + " failed");
        System.out.println();
        for (CaseOutcome outcome : report.outcomes()) {
            String mark = outcome.score().pass() ? "PASS" : "FAIL";
            DemoSupport.bullet("[" + mark + "] " + outcome.case_().id() + " - " + outcome.score().detail());
        }
    }

    /** A deterministic offline agent: looks each prompt up in {@link #SCRIPT} and returns a TEXT answer. */
    private static final class ScriptedAgentService implements AgentService, AgentSession {

        @Override
        public AgentSession open(SessionRequest req) {
            return this;
        }

        @Override
        public AgentAnswer ask(UserMessage msg) {
            String text = SCRIPT.getOrDefault(msg.text(), "I don't know.");
            return new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), new RunId("eval-" + msg.text().hashCode()));
        }

        @Override
        public void askStream(UserMessage msg, AnswerSink sink) {
            throw new UnsupportedOperationException("not used by the eval harness");
        }

        @Override
        public void close() {
        }
    }
}
