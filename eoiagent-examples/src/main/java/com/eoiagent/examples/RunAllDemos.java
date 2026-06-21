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
        System.out.println("Done. All demos ran offline (no network required).");
    }
}
