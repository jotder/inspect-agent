package com.eoiagent.safety;

import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.GuardrailVerdict;
import dev.langchain4j.internal.Json;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Output {@link Guardrail} (Flow A step 5): validates a model's structured output against an expected
 * JSON schema and drives a bounded reprompt. Pure Java + local JSON parsing — no model, no network
 * (Flow invariant #3), deterministic.
 *
 * <p>Verdicts (guardrails spec, AC3): a valid output → {@code PASS} (the output passes through as
 * {@code transformedText}); a malformed/non-conforming output → {@code RETRY} while the caller's
 * reprompt budget remains, else {@code FAIL}. On {@code RETRY}/{@code FAIL} the offending output is
 * stripped ({@code transformedText == null}) and the validation error is returned as the
 * {@code message} to feed back as a reprompt hint. The adapter is <strong>stateless</strong>: the
 * caller owns the retry counter and passes the remaining budget in
 * {@code GuardrailInput.ctx().attributes()} under {@value #RETRIES_LEFT_ATTR} (spec recommendation).
 *
 * <p>Validation is intentionally lightweight — JSON well-formedness plus presence of every field in
 * the schema's {@code "required"} array (mirrors the tool {@code ArgumentValidator}); deeper
 * type/constraint checking is out of scope (guardrails spec). An absent/blank schema means there is
 * nothing to validate → {@code PASS}.
 */
public final class SchemaOutputGuardrail implements Guardrail {

    /** Caller-supplied remaining reprompt budget; {@code RETRY} while {@code > 0}, else {@code FAIL}. */
    public static final String RETRIES_LEFT_ATTR = "retriesLeft";

    @Override
    public GuardrailResult check(GuardrailInput in) {
        Objects.requireNonNull(in, "in");
        if (in.phase() != GuardrailPhase.OUTPUT) {
            return new GuardrailResult(GuardrailVerdict.PASS, "", in.text()); // input guardrails handle INPUT
        }
        try {
            String error = validate(in.text(), in.expectedJsonSchema());
            if (error == null) {
                return new GuardrailResult(GuardrailVerdict.PASS, "", in.text()); // valid → proceed
            }
            if (retriesLeft(in) > 0) {
                // Strip the offending output; the message is the reprompt hint fed back to the model.
                return new GuardrailResult(GuardrailVerdict.RETRY, "schema validation failed: " + error, null);
            }
            return new GuardrailResult(GuardrailVerdict.FAIL,
                    "schema validation failed after retries: " + error, null);
        } catch (RuntimeException e) {
            // Fail closed: never let unvalidated output through if the guardrail faults unexpectedly.
            return new GuardrailResult(GuardrailVerdict.FAIL, "guardrail error: " + e.getMessage(), null);
        }
    }

    /** @return {@code null} when the output conforms, otherwise a human-readable validation error. */
    @SuppressWarnings("unchecked")
    static String validate(String output, String schema) {
        if (schema == null || schema.isBlank()) {
            return null; // no schema → nothing to validate
        }
        if (output == null || output.isBlank()) {
            return "empty output";
        }
        Map<String, Object> parsed;
        try {
            parsed = Json.fromJson(output, Map.class);
        } catch (RuntimeException e) {
            return "output is not valid JSON";
        }
        if (parsed == null) {
            return "output is not a JSON object";
        }
        Map<String, Object> schemaMap;
        try {
            schemaMap = Json.fromJson(schema, Map.class);
        } catch (RuntimeException e) {
            return null; // an unparseable schema is a tool-authoring bug, not the model's fault — don't fail
        }
        if (schemaMap != null && schemaMap.get("required") instanceof List<?> required) {
            for (Object field : required) {
                String name = String.valueOf(field);
                if (!parsed.containsKey(name) || parsed.get(name) == null) {
                    return "missing required field '" + name + "'";
                }
            }
        }
        return null;
    }

    private static int retriesLeft(GuardrailInput in) {
        if (in.ctx() == null || in.ctx().attributes() == null) {
            return 0;
        }
        String value = in.ctx().attributes().get(RETRIES_LEFT_ATTR);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
