package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.InlineArtifact;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.AnswerSink;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.time.Instant;

/**
 * T-355: real token streaming through {@code askStream}. Corrects the misconception that
 * <strong>streaming is a cosmetic typing effect</strong>: tokens are forwarded as the model emits
 * them, so the user reads the beginning of the answer while the rest is still being generated —
 * the metric that matters for embedded help is time-to-first-token, not total time. Offline the
 * stub splits by word; against a live Ollama these are the model's actual chunks.
 */
public final class StreamingAnswerDemo {

    private StreamingAnswerDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Streaming answers: tokens arrive while the model still thinks");

        System.out.println("  MISCONCEPTION: \"streaming is a fake typing animation\"");
        System.out.println("  REALITY:       chunks arrive as generated; the host renders immediately;");
        System.out.println("                 a backend that cannot stream degrades to one chunk, never fails");
        System.out.println();

        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyText("Ingestion runs nightly: the nightly-load pipeline refreshes curated "
                        + "datasets from the raw zone every day at 02:00 UTC.")
                .defaultReplyText("See the Acme docs.")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(DemoSupport.chooseGateway(scripted))
                .start()) {

            AgentSession session = platform.agentService().open(DemoSupport.session(Role.USER));

            String question = "How often does ingestion run?";
            System.out.println("  Q: " + question);
            System.out.print("  A: ");
            session.askStream(new UserMessage(question, null, Instant.now()), new AnswerSink() {
                @Override
                public void onToken(String token) {
                    System.out.print(token + " ");
                    System.out.flush(); // each chunk is visible the moment it arrives
                }

                @Override
                public void onArtifact(InlineArtifact artifact) {
                }

                @Override
                public void onComplete(AgentAnswer finalAnswer) {
                    System.out.println();
                    System.out.println("  [complete: kind=" + finalAnswer.kind()
                            + ", citations=" + finalAnswer.citations().size() + "]");
                }

                @Override
                public void onError(EoiAgentException error) {
                    System.out.println();
                    System.out.println("  [error: " + error.getMessage() + "]");
                }
            });
            session.close();
        }

        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("askStream forwards genuine model tokens (no post-hoc splitting)");
        DemoSupport.bullet("tool-using turns stream nothing; only the answer turn produces tokens");
        DemoSupport.bullet("optimize for time-to-first-token in embedded help UX");
    }
}
