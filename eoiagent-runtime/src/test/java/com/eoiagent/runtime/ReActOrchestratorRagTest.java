package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Citation;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.knowledge.Retriever;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-352: retrieval in the ReAct loop — the corpus is consulted per QA turn, the retrieved context
 * reaches the model as a SYSTEM message, the RETRIEVAL is audited in the shape the eval harness
 * reconstructs ({@code details.sourceIds}), and the answer carries citations. RAG is a per-question
 * platform step, not "the model knows our docs".
 */
class ReActOrchestratorRagTest {

    private static final Citation DOCS = new Citation("acme-docs", "Acme overview", "/acme/docs/overview.md");

    /** Retriever fake that records queries and returns scripted chunks. */
    private static final class FakeRetriever implements Retriever {
        final List<RetrievalQuery> queries = new ArrayList<>();
        private final List<RetrievedChunk> chunks;

        FakeRetriever(List<RetrievedChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public List<RetrievedChunk> retrieve(RetrievalQuery query) {
            queries.add(query);
            return chunks;
        }
    }

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
    }

    private static ReActOrchestrator orchestrator(ScriptedGateway gateway, RecordingAuditSink sink,
                                                  Retriever retriever, String systemPrompt) {
        DefaultToolRegistry registry = new DefaultToolRegistry(new AllowAllPolicyEngine(), sink);
        registry.register(new FixedTool("echo", "ok"));
        return ReActOrchestrator.builder()
                .gateway(gateway).tools(registry).scratchpad(new InMemoryScratchpad())
                .audit(sink).config(new FakeRuntimeConfig(12, 8192))
                .retriever(retriever)
                .systemPrompts(systemPrompt == null ? null : kind -> systemPrompt)
                .build();
    }

    @Test
    void qaTurnRetrievesInjectsContextAndCites() {
        FakeRetriever retriever = new FakeRetriever(List.of(
                new RetrievedChunk("Ingestion runs nightly at 02:00 UTC.", 0.91, DOCS)));
        ScriptedGateway gateway = new ScriptedGateway().finalText("Nightly at 02:00 UTC.");
        RecordingAuditSink sink = new RecordingAuditSink();

        AgentRun run = orchestrator(gateway, sink, retriever, "You are the Acme assistant.")
                .run(new Goal("How often does ingestion run?", GoalKind.QA), ctx());

        // Query used the goal text and the configured top-k default.
        assertThat(retriever.queries).hasSize(1);
        assertThat(retriever.queries.get(0).text()).isEqualTo("How often does ingestion run?");
        assertThat(retriever.queries.get(0).k()).isEqualTo(4);

        // The model saw one SYSTEM message carrying both the persona prompt and the tagged context.
        assertThat(gateway.lastMessages.get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(gateway.lastMessages.get(0).text())
                .contains("You are the Acme assistant.")
                .contains("[source: acme-docs] Ingestion runs nightly at 02:00 UTC.");

        // RETRIEVAL audited in the eval-reconstructable shape, and the answer cites the source.
        AuditEvent retrieval = sink.events.stream()
                .filter(e -> e.kind() == AuditKind.RETRIEVAL).findFirst().orElseThrow();
        assertThat(retrieval.details().get("sourceIds")).isEqualTo(List.of("acme-docs"));
        assertThat(run.answer().citations()).containsExactly(DOCS);
    }

    @Test
    void emptyRetrievalStillAuditsButAddsNoContextAndNoCitations() {
        FakeRetriever retriever = new FakeRetriever(List.of());
        ScriptedGateway gateway = new ScriptedGateway().finalText("answer");
        RecordingAuditSink sink = new RecordingAuditSink();

        AgentRun run = orchestrator(gateway, sink, retriever, null)
                .run(new Goal("anything indexed?", GoalKind.QA), ctx());

        assertThat(sink.kinds()).contains(AuditKind.RETRIEVAL);
        assertThat(gateway.lastMessages.get(0).role()).isEqualTo(ChatRole.USER); // no SYSTEM message
        assertThat(run.answer().citations()).isEmpty();
    }

    @Test
    void nonQaGoalsSkipRetrieval() {
        FakeRetriever retriever = new FakeRetriever(List.of(
                new RetrievedChunk("irrelevant", 0.5, DOCS)));
        ScriptedGateway gateway = new ScriptedGateway().finalText("navigating");
        RecordingAuditSink sink = new RecordingAuditSink();

        orchestrator(gateway, sink, retriever, null)
                .run(new Goal("analyze the pipeline failure trend", GoalKind.ANALYSIS), ctx());

        assertThat(retriever.queries).isEmpty();
        assertThat(sink.kinds()).doesNotContain(AuditKind.RETRIEVAL);
    }

    @Test
    void systemPromptAloneIsInjectedWithoutARetriever() {
        ScriptedGateway gateway = new ScriptedGateway().finalText("hi");

        orchestrator(gateway, new RecordingAuditSink(), null, "Persona only.")
                .run(new Goal("hello", GoalKind.QA), ctx());

        assertThat(gateway.lastMessages.get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(gateway.lastMessages.get(0).text()).isEqualTo("Persona only.");
    }
}
