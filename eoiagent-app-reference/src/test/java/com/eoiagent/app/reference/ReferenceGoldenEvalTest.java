package com.eoiagent.app.reference;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.eval.CaseOutcome;
import com.eoiagent.eval.DefaultEvalHarness;
import com.eoiagent.eval.EvalHarness;
import com.eoiagent.eval.EvalReport;
import com.eoiagent.eval.EvalSuite;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T-116 AC3: the reference golden set passes when run through the assembled platform, OFFLINE. */
class ReferenceGoldenEvalTest {

    @Test
    void acmeGoldenSetPassesThroughTheAssembledPlatformOffline() {
        EvalSuite suite = AcmeGoldenCases.suite();
        StubLlmGateway gateway = AcmeGoldenCases.scriptedGateway();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(gateway)
                .start()) {

            EvalHarness harness = new DefaultEvalHarness();
            EvalReport report = harness.run(suite, platform.agentService(), DeploymentProfile.OFFLINE);

            assertThat(report.total()).isEqualTo(suite.cases().size());
            assertThat(report.failed())
                    .as("failing cases: %s", failures(report))
                    .isZero();
            assertThat(report.passed()).isEqualTo(report.total());
        }
    }

    private static String failures(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        for (CaseOutcome outcome : report.outcomes()) {
            if (!outcome.score().pass()) {
                sb.append(outcome.case_().id()).append("=> ").append(outcome.score().detail()).append("; ");
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
