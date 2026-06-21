package com.eoiagent.examples;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;

import java.time.Instant;
import java.util.List;

/**
 * Showcases an end-to-end Flow-A session: open a session, ask product questions, and print each typed
 * {@link AgentAnswer} together with the audit trail the platform records (via {@link ConsoleAuditSink}).
 * Runs offline with scripted replies, or against a live local Ollama if one is reachable.
 */
public final class QaSessionDemo {

    private static final List<String> QUESTIONS = List.of(
            "How often does ingestion run?",
            "What are the lakehouse zones?",
            "Where do I see revenue?");

    private QaSessionDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Q&A session (Flow A) with audit trail");

        // Scripted offline answers, one per question, used only when no live Ollama is present.
        StubLlmGateway offline = StubLlmGateway.builder()
                .replyText("Ingestion runs nightly via the nightly-load pipeline at 02:00 UTC.")
                .replyText("The Acme lakehouse has three zones: raw, curated, and mart.")
                .replyText("Open the KPI Dashboard and select the revenue metric.")
                .defaultReplyText("See the Acme product docs for details.")
                .build();

        try (AgentPlatform platform = DemoSupport.boot(DemoSupport.chooseGateway(offline), new ConsoleAuditSink())) {
            AgentSession session = platform.agentService().open(DemoSupport.session(Role.ANALYST));
            for (String question : QUESTIONS) {
                System.out.println();
                System.out.println("  Q: " + question);
                AgentAnswer answer = session.ask(new UserMessage(question, null, Instant.now()));
                System.out.println("  A: [" + answer.kind() + "] " + answer.text());
            }
            session.close();
        }
    }
}
