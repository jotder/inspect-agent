package com.eoiagent.examples;

/**
 * Runs every demo in sequence — the default {@code exec:java} entry point. Each showcases one part of
 * the platform: bootstrap, the RAG corpus + read-only tools, navigation, policy/profiles, and a Q&A
 * session. Fully offline by default; uses a local Ollama automatically if one is reachable.
 */
public final class RunAllDemos {

    private RunAllDemos() {
    }

    public static void main(String[] args) {
        System.out.println("############################################################");
        System.out.println("#  EOI Agent - Acme Lakehouse reference pack, live demos    #");
        System.out.println("############################################################");

        PlatformBootstrapDemo.main(args);
        RagAndToolsDemo.main(args);
        NavigationDemo.main(args);
        PolicyAndProfilesDemo.main(args);
        QaSessionDemo.main(args);

        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("  Phase 2 capabilities (agentic flows + safety)");
        System.out.println("------------------------------------------------------------");

        MutatingApprovalDemo.main(args);   // Flow C: plan -> approve -> act + RBAC (T-201..T-204)
        SupervisorDemo.main(args);         // Flow D: supervisor + sub-agents (T-205)
        SummarizingMemoryDemo.main(args);  // running-summary chat memory (T-207)
        AdvancedRetrievalDemo.main(args);  // rewrite + route + re-rank retrieval (T-208)
        McpGatingDemo.main(args);          // MCP tool gating (T-209)
        OutputGuardrailDemo.main(args);    // schema output guardrail + reprompt (T-210)
        Phase2EvalDemo.main(args);         // eval harness over a Phase-2 golden set (T-211)

        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("  Phase 3.5 integration (the live path, closed)");
        System.out.println("------------------------------------------------------------");

        MultiTurnMemoryDemo.main(args);    // session memory in the live loop (T-351)
        RagCitationsDemo.main(args);       // RAG + citations in the live loop (T-352)

        System.out.println();
        System.out.println("Done. All demos ran offline (no network required).");
    }
}
