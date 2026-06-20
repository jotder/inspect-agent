package com.eoiagent.safety;

import com.eoiagent.core.GuardrailResult;

/** Port that checks input/output text against a guardrail. */
public interface Guardrail {

    GuardrailResult check(GuardrailInput in);
}
