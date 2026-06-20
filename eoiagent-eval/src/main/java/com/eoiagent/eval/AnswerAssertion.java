package com.eoiagent.eval;

/** An assertion over an answer's text using a match mode and a minimum judge score. */
public record AnswerAssertion(MatchMode mode, String expected, double minScore) {
}
