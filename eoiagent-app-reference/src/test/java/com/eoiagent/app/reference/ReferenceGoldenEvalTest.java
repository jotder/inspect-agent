package com.eoiagent.app.reference;

import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.eval.CaseOutcome;
import com.eoiagent.eval.CompositeScorer;
import com.eoiagent.eval.DefaultEvalHarness;
import com.eoiagent.eval.EvalHarness;
import com.eoiagent.eval.EvalReport;
import com.eoiagent.eval.EvalSuite;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-116 AC3 (+T-352): the reference golden set passes when run through the assembled platform,
 * OFFLINE — including the cited cases, whose citations the harness reconstructs from the live
 * RETRIEVAL audit events (real ONNX retrieval over the bundled corpus; only the chat model is
 * scripted).
 */
class ReferenceGoldenEvalTest {

    @Test
    void acmeGoldenSetPassesThroughTheAssembledPlatformOffline() {
        EvalSuite suite = AcmeGoldenCases.suite();
        StubLlmGateway gateway = AcmeGoldenCases.scriptedGateway();
        RecordingAuditSink sink = new RecordingAuditSink();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(gateway)
                .auditSink(sink)
                .start()) {

            EvalHarness harness = new DefaultEvalHarness(new CompositeScorer(), () -> List.copyOf(sink.events));
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

    private static final class RecordingAuditSink implements AuditSink {
        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }
}
