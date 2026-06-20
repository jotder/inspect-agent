package com.eoiagent.eval;

import com.eoiagent.core.DeploymentProfile;
import java.time.Instant;
import java.util.List;

/** The aggregate result of running a suite: pass/fail counts and per-case outcomes. */
public record EvalReport(String suite,
                         DeploymentProfile profile,
                         int total,
                         int passed,
                         int failed,
                         List<CaseOutcome> outcomes,
                         Instant at) {
}
