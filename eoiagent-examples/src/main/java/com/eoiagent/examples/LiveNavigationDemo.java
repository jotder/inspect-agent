package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.time.Instant;
import java.util.Map;

/**
 * T-353: NavigationIntent through the live loop — the signature embeddable-product behavior.
 * Corrects the misconception that <strong>the agent operates the UI</strong>: it never clicks or
 * routes anything. The model proposes the reserved {@code navigate_to_page} tool call, the
 * platform validates it against the pack's {@code NavigationCatalog} (unknown pages and missing
 * params are rejected back to the model), and the host receives a <em>typed</em>
 * {@link NavigationIntent} it can route however it wants. The agent suggests; the host decides.
 */
public final class LiveNavigationDemo {

    private LiveNavigationDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Live navigation: the agent proposes a typed intent, the host routes");

        System.out.println("  MISCONCEPTION: \"the agent opens pages / drives the UI\"");
        System.out.println("  REALITY:       the answer is a typed NavigationIntent; your product");
        System.out.println("                 decides how (and whether) to route");
        System.out.println();

        // Scripted model: first proposes a bogus page (watch it get rejected and corrected),
        // then the valid KPI-dashboard proposal. With a live model, T-350's tool mapping makes
        // this exact flow work unscripted.
        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyToolCalls(new ToolCall(NavigationIntent.TOOL_NAME,
                        Map.of("pageId", "revenue-page"), null))
                .replyToolCalls(new ToolCall(NavigationIntent.TOOL_NAME,
                        Map.of("pageId", "kpi-dashboard",
                                "params", Map.of("metric", "revenue", "period", "last-quarter"),
                                "rationale", "Revenue by period lives on the KPI Dashboard."), null))
                .defaultReplyText("See the KPI documentation.")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(scripted)
                .auditSink(new ConsoleAuditSink())
                .start()) {

            AgentSession session = platform.agentService().open(DemoSupport.session(Role.USER));

            String question = "Where can I see last quarter's revenue?";
            System.out.println("  Q: " + question);
            System.out.println("  (the model first proposes page 'revenue-page' - not in the catalog -");
            System.out.println("   the platform rejects it and the model corrects itself)");
            System.out.println();
            AgentAnswer answer = session.ask(new UserMessage(question, null, Instant.now()));

            System.out.println();
            System.out.println("  Answer kind:  " + answer.kind());
            NavigationIntent intent = answer.navigation();
            if (intent != null) {
                DemoSupport.kv("targetPageId", intent.targetPageId());
                DemoSupport.kv("parameters", intent.parameters());
                DemoSupport.kv("rationale", intent.rationale());
            }
            session.close();
        }

        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("navigation is a validated tool call, not free text the host must parse");
        DemoSupport.bullet("unknown pages / missing params bounce back so the model self-corrects");
        DemoSupport.bullet("the host gets a typed intent - route it, confirm it, or ignore it");
    }
}
