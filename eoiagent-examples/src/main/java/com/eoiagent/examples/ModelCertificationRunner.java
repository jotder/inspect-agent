package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.eval.AnswerAssertion;
import com.eoiagent.eval.CaseOutcome;
import com.eoiagent.eval.CompositeScorer;
import com.eoiagent.eval.DefaultEvalHarness;
import com.eoiagent.eval.EvalCase;
import com.eoiagent.eval.EvalReport;
import com.eoiagent.eval.EvalSuite;
import com.eoiagent.eval.Expectation;
import com.eoiagent.eval.MatchMode;
import com.eoiagent.eval.NavigationAssertion;
import com.eoiagent.eval.ToolCallAssertion;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T-356 / ADR-0013 §3: the model certification gate. "Should we adopt model X?" is a MEASUREMENT,
 * not an opinion — this runner boots the reference pack against a LIVE local endpoint (selected
 * purely by config, per ADR-0013 §1) and scores the capabilities the platform actually depends on:
 * RAG-grounded answering, tool-call fidelity, typed navigation, and citation provenance. A model
 * that passes is certified for this platform version; one that fails is rejected with a scored
 * report naming the broken capability.
 *
 * <p>Endpoint via env (defaults target a local Ollama):
 * {@code EOIAGENT_CERT_PROVIDER} (ollama | openai-compatible), {@code EOIAGENT_CERT_BASE_URL},
 * {@code EOIAGENT_CERT_MODEL} — e.g. certify Ornith 1.0 with
 * {@code EOIAGENT_CERT_MODEL=ornith-1.0-9b}. Fully read-only; no data leaves the machine.
 */
public final class ModelCertificationRunner {

    private ModelCertificationRunner() {
    }

    /** The capability suite every candidate model must pass. Loose on wording, strict on behavior. */
    public static EvalSuite suite() {
        List<EvalCase> cases = List.of(
                new EvalCase("cert-rag-grounding",
                        "According to the product docs, at what time (UTC) does the nightly-load "
                                + "pipeline refresh curated datasets?",
                        null, Role.USER,
                        new Expectation(AnswerKind.TEXT,
                                new AnswerAssertion(MatchMode.CONTAINS, "02:00", 0.0),
                                List.of(), null, List.of("acme-docs")),
                        Set.of("certification", "rag")),
                new EvalCase("cert-tool-call-fidelity",
                        "Use the getPipelineStatus tool to check the pipeline with id 'nightly-load' "
                                + "and tell me its last run status.",
                        null, Role.USER,
                        new Expectation(AnswerKind.TEXT,
                                new AnswerAssertion(MatchMode.CONTAINS, "SUCCEEDED", 0.0),
                                List.of(new ToolCallAssertion("getPipelineStatus",
                                        Map.of("pipelineId", "nightly-load"), false)),
                                null, List.of()),
                        Set.of("certification", "tools")),
                new EvalCase("cert-navigation",
                        "Take me to the KPI dashboard for the revenue metric.",
                        null, Role.USER,
                        new Expectation(AnswerKind.NAVIGATION, null, List.of(),
                                new NavigationAssertion("kpi-dashboard", Map.of("metric", "revenue"),
                                        null, null),
                                List.of()),
                        Set.of("certification", "navigation")));
        return new EvalSuite("model-certification", cases);
    }

    /** Boots the platform against the endpoint (config-first, ADR-0013 §1) and runs the suite. */
    public static EvalReport certify(String provider, String baseUrl, String modelId) {
        RecordingAuditSink sink = new RecordingAuditSink();
        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .configProvider(new DemoConfig(DeploymentProfile.OFFLINE, Map.of(
                        "eoiagent.model.chat.provider", provider,
                        "eoiagent.model.chat.baseUrl", baseUrl,
                        "eoiagent.model.chat.modelId", modelId)))
                .auditSink(sink)
                .start()) {
            DefaultEvalHarness harness = new DefaultEvalHarness(
                    new CompositeScorer(), () -> List.copyOf(sink.events));
            return harness.run(suite(), platform.agentService(), DeploymentProfile.OFFLINE);
        }
    }

    public static void main(String[] args) {
        String provider = env("EOIAGENT_CERT_PROVIDER", "ollama");
        String baseUrl = env("EOIAGENT_CERT_BASE_URL", "http://localhost:11434");
        String modelId = env("EOIAGENT_CERT_MODEL", DemoSupport.OLLAMA_MODEL);

        DemoSupport.header("Model certification: " + provider + "/" + modelId);
        DemoSupport.kv("endpoint", baseUrl);
        DemoSupport.kv("suite", suite().cases().size() + " capability cases (RAG, tools, navigation)");

        if (!reachable(baseUrl)) {
            System.out.println();
            System.out.println("  Endpoint not reachable - start a local model server first, e.g.:");
            DemoSupport.bullet("ollama pull " + modelId + " && ollama serve");
            DemoSupport.bullet("then: mvn -q -pl eoiagent-examples exec:java "
                    + "-Dexec.mainClass=com.eoiagent.examples.ModelCertificationRunner");
            return;
        }

        EvalReport report = certify(provider, baseUrl, modelId);
        System.out.println();
        for (CaseOutcome outcome : report.outcomes()) {
            String mark = outcome.score().pass() ? "PASS" : "FAIL";
            System.out.println("  [" + mark + "] " + outcome.case_().id()
                    + (outcome.score().pass() ? "" : "  -> " + outcome.score().detail()));
        }
        System.out.println();
        boolean certified = report.failed() == 0;
        System.out.println("  VERDICT: " + (certified
                ? "CERTIFIED - " + modelId + " passes all capability gates on this platform version"
                : "REJECTED - " + report.failed() + "/" + report.total()
                        + " capability gates failed (see details above)"));
    }

    private static String env(String name, String fallback) {
        String prop = System.getProperty(name); // sysprop wins (lets tests target a dead endpoint)
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean reachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 500);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static final class RecordingAuditSink implements AuditSink {
        final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }
}
