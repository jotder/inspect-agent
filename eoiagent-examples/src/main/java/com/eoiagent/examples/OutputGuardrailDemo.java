package com.eoiagent.examples;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.Role;
import com.eoiagent.safety.GuardrailInput;
import com.eoiagent.safety.GuardrailPhase;
import com.eoiagent.safety.SchemaOutputGuardrail;

import java.util.Map;

/**
 * Phase-2 — output guardrail (T-210). {@link SchemaOutputGuardrail} validates a model's structured
 * output against an expected JSON schema and drives a bounded reprompt — pure Java, no model, no
 * network. The caller owns the retry budget and passes it in {@code AgentContext.attributes()} under
 * {@code retriesLeft}.
 *
 * <p>Three outcomes against a schema requiring {@code targetPageId}:
 * <ul>
 *   <li><b>PASS</b> — conforming output passes through unchanged.</li>
 *   <li><b>RETRY</b> — non-conforming output with budget remaining: output stripped, the validation
 *       error is returned as a reprompt hint.</li>
 *   <li><b>FAIL</b> — non-conforming output with the budget exhausted.</li>
 * </ul>
 */
public final class OutputGuardrailDemo {

    private static final String SCHEMA = "{\"required\":[\"targetPageId\"]}";

    private OutputGuardrailDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Output guardrail: schema validation + bounded reprompt");
        DemoSupport.kv("schema", SCHEMA);

        SchemaOutputGuardrail guardrail = new SchemaOutputGuardrail();

        check(guardrail, "conforming output, 2 retries left", "{\"targetPageId\":\"orders\"}", 2);
        check(guardrail, "missing targetPageId, 2 retries left", "{\"foo\":\"bar\"}", 2);
        check(guardrail, "missing targetPageId, 0 retries left", "{\"foo\":\"bar\"}", 0);
    }

    private static void check(SchemaOutputGuardrail guardrail, String label, String output, int retriesLeft) {
        AgentContext ctx = DemoSupport.context(Role.USER, DeploymentProfile.OFFLINE,
                Map.of(SchemaOutputGuardrail.RETRIES_LEFT_ATTR, Integer.toString(retriesLeft)));
        GuardrailResult result = guardrail.check(new GuardrailInput(GuardrailPhase.OUTPUT, output, SCHEMA, ctx));

        System.out.println();
        DemoSupport.bullet(label + " -> " + output);
        DemoSupport.kv("  verdict", result.verdict());
        DemoSupport.kv("  passed-through", result.transformedText() == null ? "(stripped)" : result.transformedText());
        if (!result.message().isBlank()) {
            DemoSupport.kv("  reprompt hint", result.message());
        }
    }
}
