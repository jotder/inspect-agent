package com.eoiagent.eval;

import java.util.List;

/** A named collection of eval cases. */
public record EvalSuite(String name, List<EvalCase> cases) {
}
