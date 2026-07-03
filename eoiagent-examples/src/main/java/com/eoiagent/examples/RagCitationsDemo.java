package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Citation;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.time.Instant;

/**
 * T-352: RAG in the live loop, with citations. Clears up the second-most common misconception:
 * <strong>"RAG means the model knows our documents." It does not.</strong> Nothing is "taught" to
 * the model — for every question the platform embeds the query, searches the vector store, and
 * injects only the best-matching chunks into that one call; the citations on the answer prove
 * which sources grounded it. The retrieval here is real (ONNX embeddings over the pack's bundled
 * corpus, fully offline) — only the chat reply is scripted.
 */
public final class RagCitationsDemo {

    private RagCitationsDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("RAG + citations: retrieval happens per question, not at training time");

        System.out.println("  MISCONCEPTION: \"RAG = the model was trained on / knows our docs\"");
        System.out.println("  REALITY:       each question triggers embed -> vector search -> inject top-k;");
        System.out.println("                 the model sees only those chunks, and citations prove provenance");
        System.out.println();

        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyText("Ingestion runs nightly; the nightly-load pipeline refreshes curated data at 02:00 UTC.")
                .defaultReplyText("See the Acme docs.")
                .build();

        // Bootstrap ingests the pack's corpus (real ONNX embeddings, ~seconds, offline).
        System.out.println("  Booting platform: ingesting the pack corpus into the vector store...");
        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(scripted)
                .auditSink(new ConsoleAuditSink())   // watch for the RETRIEVAL audit line below
                .start()) {

            AgentSession session = platform.agentService().open(DemoSupport.session(Role.USER));

            String question = "How often does ingestion run?";
            System.out.println();
            System.out.println("  Q: " + question);
            AgentAnswer answer = session.ask(new UserMessage(question, null, Instant.now()));
            System.out.println("  A: " + answer.text());

            System.out.println();
            System.out.println("  Citations (which sources grounded this answer):");
            for (Citation c : answer.citations()) {
                DemoSupport.bullet(c.sourceId() + " - " + c.title() + " (" + c.locator() + ")");
            }
            session.close();
        }

        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("nothing was 'taught' to the model; context is fetched per question");
        DemoSupport.bullet("every retrieval is audited (RETRIEVAL) - compliance sees what the model saw");
        DemoSupport.bullet("citations come from chunk metadata, so answers are traceable to sources");
    }
}
