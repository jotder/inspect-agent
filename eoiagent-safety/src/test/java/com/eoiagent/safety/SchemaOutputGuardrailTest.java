package com.eoiagent.safety;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.GuardrailVerdict;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Output guardrail (T-210, guardrails spec AC3): PASS for schema-conforming output, RETRY while the
 * reprompt budget remains, FAIL once exhausted; the offending output is stripped on RETRY/FAIL.
 */
class SchemaOutputGuardrailTest {

    private static final String SCHEMA = "{\"required\":[\"targetPageId\"]}";

    private static GuardrailInput output(String text, int retriesLeft) {
        AgentContext ctx = new AgentContext(new AppId("a"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null,
                Map.of(SchemaOutputGuardrail.RETRIES_LEFT_ATTR, Integer.toString(retriesLeft)));
        return new GuardrailInput(GuardrailPhase.OUTPUT, text, SCHEMA, ctx);
    }

    private final SchemaOutputGuardrail guardrail = new SchemaOutputGuardrail();

    @Test
    void conformingOutputPasses() { // AC3 (PASS)
        GuardrailResult result = guardrail.check(output("{\"targetPageId\":\"orders\"}", 2));

        assertThat(result.verdict()).isEqualTo(GuardrailVerdict.PASS);
        assertThat(result.transformedText()).isEqualTo("{\"targetPageId\":\"orders\"}"); // passes through
    }

    @Test
    void malformedOutputRetriesWhileBudgetRemains() { // AC3 (RETRY)
        GuardrailResult result = guardrail.check(output("{\"foo\":\"bar\"}", 2));

        assertThat(result.verdict()).isEqualTo(GuardrailVerdict.RETRY);
        assertThat(result.message()).contains("targetPageId"); // reprompt hint
        assertThat(result.transformedText()).isNull();          // offending output stripped
    }

    @Test
    void nonJsonOutputRetriesWhileBudgetRemains() { // AC3 (RETRY on unparseable output)
        GuardrailResult result = guardrail.check(output("I cannot answer that.", 1));

        assertThat(result.verdict()).isEqualTo(GuardrailVerdict.RETRY);
        assertThat(result.transformedText()).isNull();
    }

    @Test
    void malformedOutputFailsWhenBudgetExhausted() { // AC3 (FAIL)
        GuardrailResult result = guardrail.check(output("{\"foo\":\"bar\"}", 0));

        assertThat(result.verdict()).isEqualTo(GuardrailVerdict.FAIL);
        assertThat(result.transformedText()).isNull();
    }

    @Test
    void inputPhaseIsPassedThrough() {
        AgentContext ctx = new AgentContext(new AppId("a"), new SessionId("s"), new UserId("u"),
                Role.USER, DeploymentProfile.OFFLINE, null, Map.of());
        GuardrailResult result = guardrail.check(
                new GuardrailInput(GuardrailPhase.INPUT, "anything", SCHEMA, ctx));

        assertThat(result.verdict()).isEqualTo(GuardrailVerdict.PASS); // output guardrail ignores INPUT
    }

    @Test
    void isDeterministic() { // AC7 (spec)
        GuardrailInput in = output("{\"foo\":\"bar\"}", 1);
        assertThat(guardrail.check(in)).isEqualTo(guardrail.check(in));
    }
}
