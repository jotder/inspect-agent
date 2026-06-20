package com.eoiagent.eval;

/** The outcome of scoring one case: pass flag, fractional value and a human-readable detail. */
public record Score(boolean pass, double value, String detail) {
}
