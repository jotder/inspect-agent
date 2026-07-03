package com.eoiagent.examples;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke-tests every demo's {@code main} offline (forces the stub gateway, no Ollama probe), asserting
 * each runs to completion without throwing. This keeps the samples honest as the platform API evolves:
 * if a demo stops compiling or assembling, CI catches it here rather than a user hitting it.
 */
class DemoSmokeTest {

    @BeforeAll
    static void forceOffline() {
        System.setProperty("eoiagent.demo.offline", "true");
    }

    @Test
    void platformBootstrapDemoRuns() {
        assertThatCode(() -> PlatformBootstrapDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void ragAndToolsDemoRuns() {
        assertThatCode(() -> RagAndToolsDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void navigationDemoRuns() {
        assertThatCode(() -> NavigationDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void policyAndProfilesDemoRuns() {
        assertThatCode(() -> PolicyAndProfilesDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void qaSessionDemoRuns() {
        assertThatCode(() -> QaSessionDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void mutatingApprovalDemoRuns() {
        assertThatCode(() -> MutatingApprovalDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void supervisorDemoRuns() {
        assertThatCode(() -> SupervisorDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void summarizingMemoryDemoRuns() {
        assertThatCode(() -> SummarizingMemoryDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void advancedRetrievalDemoRuns() {
        assertThatCode(() -> AdvancedRetrievalDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void mcpGatingDemoRuns() {
        assertThatCode(() -> McpGatingDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void outputGuardrailDemoRuns() {
        assertThatCode(() -> OutputGuardrailDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void phase2EvalDemoRuns() {
        assertThatCode(() -> Phase2EvalDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void multiTurnMemoryDemoRuns() {
        assertThatCode(() -> MultiTurnMemoryDemo.main(new String[0])).doesNotThrowAnyException();
    }

    @Test
    void runAllDemosRuns() {
        assertThatCode(() -> RunAllDemos.main(new String[0])).doesNotThrowAnyException();
    }
}
