package com.eoiagent.app.reference;

import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.Role;
import com.eoiagent.eval.AnswerAssertion;
import com.eoiagent.eval.EvalCase;
import com.eoiagent.eval.EvalSuite;
import com.eoiagent.eval.Expectation;
import com.eoiagent.eval.MatchMode;
import com.eoiagent.model.StubLlmGateway;

import java.util.List;
import java.util.Set;

/**
 * The reference pack's golden set: a small offline Q&A suite run through the assembled platform. Each
 * case pairs a prompt with the substring its answer must contain and the scripted model reply that
 * produces it (the platform is driven by a {@link StubLlmGateway}, no live LLM). Since T-352 the
 * platform runs REAL retrieval over the bundled corpus (ONNX embeddings, offline) even with a
 * scripted chat model, so the overview-backed cases also assert citations; navigation cases arrive
 * with T-353.
 *
 * <p>{@link #scriptedGateway()} enqueues one reply per case <em>in suite order</em>; the harness asks
 * the cases in that order, so the FIFO stub answers each correctly.
 */
final class AcmeGoldenCases {

    private AcmeGoldenCases() {
    }

    private record Golden(String id, String prompt, Role role, String mustContain, String reply,
                          List<String> mustCite) {
    }

    private static final List<Golden> CASES = List.of(
            new Golden("qa-ingestion-cadence", "How often does ingestion run?", Role.USER,
                    "nightly", "Ingestion runs nightly via the nightly-load pipeline at 02:00 UTC.",
                    List.of("acme-docs")),
            new Golden("qa-lakehouse-zones", "What are the lakehouse zones?", Role.USER,
                    "curated", "The Acme lakehouse has three zones: raw, curated, and mart.",
                    List.of("acme-docs")),
            new Golden("qa-find-revenue", "Where do I see revenue?", Role.ANALYST,
                    "KPI", "Open the KPI Dashboard and select the revenue metric.",
                    List.of()),
            new Golden("qa-pipeline-status", "How do I check a pipeline's last run?", Role.USER,
                    "Pipeline Detail", "Open the Pipeline Detail page for that pipeline id.",
                    List.of()));

    static EvalSuite suite() {
        List<EvalCase> cases = CASES.stream()
                .map(g -> new EvalCase(g.id(), g.prompt(), null, g.role(),
                        new Expectation(AnswerKind.TEXT,
                                new AnswerAssertion(MatchMode.CONTAINS, g.mustContain(), 0.0),
                                List.of(), null, g.mustCite()),
                        Set.of("acme", "qa")))
                .toList();
        return new EvalSuite("acme-golden", cases);
    }

    static StubLlmGateway scriptedGateway() {
        StubLlmGateway.Builder builder = StubLlmGateway.builder();
        for (Golden g : CASES) {
            builder.replyText(g.reply());
        }
        return builder.build();
    }
}
