package com.eoiagent.host;

import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.GuardrailVerdict;
import com.eoiagent.safety.Guardrail;
import com.eoiagent.safety.GuardrailInput;

/** In-test {@link Guardrail} returning a fixed verdict. */
final class StubGuardrail implements Guardrail {

    private final GuardrailResult result;

    private StubGuardrail(GuardrailResult result) {
        this.result = result;
    }

    static StubGuardrail pass() {
        return new StubGuardrail(new GuardrailResult(GuardrailVerdict.PASS, "", null));
    }

    static StubGuardrail fail() {
        return new StubGuardrail(new GuardrailResult(GuardrailVerdict.FAIL, "prompt-injection", null));
    }

    static StubGuardrail redact(String transformed) {
        return new StubGuardrail(new GuardrailResult(GuardrailVerdict.REDACTED, "redacted PII", transformed));
    }

    @Override
    public GuardrailResult check(GuardrailInput in) {
        return result;
    }
}
