package com.eoiagent.eval;

import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import java.util.Set;

/** A single eval case: a prompt under a role/page plus its expected outcome and tags. */
public record EvalCase(String id,
                       String prompt,
                       PageContext page,
                       Role role,
                       Expectation expect,
                       Set<String> tags) {
}
