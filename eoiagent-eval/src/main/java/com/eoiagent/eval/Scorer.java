package com.eoiagent.eval;

/** Scores an actual run result against the expectation declared on an eval case. */
public interface Scorer {

    Score score(EvalCase expected, EvalRunResult actual);
}
