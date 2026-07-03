package com.eoiagent.examples;

import com.eoiagent.eval.CaseOutcome;
import com.eoiagent.eval.EvalReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T-356: the live-model certification gate, env-gated like the Postgres ITs so offline CI skips it.
 * Opt in with a live endpoint, e.g.:
 * {@code EOIAGENT_IT_LLM_BASE_URL=http://localhost:11434 mvn -pl eoiagent-examples test}
 * (optional: {@code EOIAGENT_IT_LLM_PROVIDER}, {@code EOIAGENT_IT_LLM_MODEL} — certify a candidate
 * like Ornith 1.0 by pointing these at it).
 */
class LiveModelCertificationTest {

    @Test
    void liveModelPassesAllCertificationGates() {
        String baseUrl = System.getenv("EOIAGENT_IT_LLM_BASE_URL");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(),
                "set EOIAGENT_IT_LLM_BASE_URL to run the live certification");
        String provider = envOr("EOIAGENT_IT_LLM_PROVIDER", "ollama");
        String model = envOr("EOIAGENT_IT_LLM_MODEL", DemoSupport.OLLAMA_MODEL);

        EvalReport report = ModelCertificationRunner.certify(provider, baseUrl, model);

        assertThat(report.failed())
                .as("failed gates: %s", failures(report))
                .isZero();
    }

    @Test
    void runnerReturnsGracefullyWhenNoEndpointIsReachable() {
        // Points the runner at a dead port; it must print instructions and return, never throw —
        // this keeps the runner (and offline CI) safe on machines without a model server.
        System.setProperty("EOIAGENT_CERT_BASE_URL", "http://localhost:1");
        try {
            assertThatCode(() -> ModelCertificationRunner.main(new String[0]))
                    .doesNotThrowAnyException();
        } finally {
            System.clearProperty("EOIAGENT_CERT_BASE_URL");
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String failures(EvalReport report) {
        StringBuilder sb = new StringBuilder();
        for (CaseOutcome outcome : report.outcomes()) {
            if (!outcome.score().pass()) {
                sb.append(outcome.case_().id()).append(" => ").append(outcome.score().detail()).append("; ");
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
