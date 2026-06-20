package com.eoiagent.eval;

/** Pairs an eval case with its score and the actual observed result. */
public record CaseOutcome(EvalCase case_, Score score, EvalRunResult actual) {
}
