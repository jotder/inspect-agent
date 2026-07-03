package com.eoiagent.safety;

import com.eoiagent.core.GuardrailResult;
import com.eoiagent.core.GuardrailVerdict;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Input {@link Guardrail} backed by the EXPERIMENTAL {@code langchain4j-guardrails} module — it both
 * implements LangChain4j's {@link InputGuardrail} (so the same instance can be wired into an LC4j AI
 * service) and our own {@link Guardrail} port, translating LC4j's {@link InputGuardrailResult} into a
 * {@link GuardrailResult}. The langchain4j-guardrails types never cross the port boundary, keeping the
 * dependency quarantined to this adapter (ADR-0010).
 *
 * <p>Detection is heuristic/regex and therefore deterministic and offline-safe (no model, no
 * network): prompt-injection phrases → {@code FAIL}; PII (email/phone/SSN) → {@code REDACTED} (or
 * {@code FAIL} under {@link PiiMode#BLOCK}); otherwise {@code PASS}. Output (schema) guardrails are a
 * later phase.
 */
public final class Lc4jInputGuardrail implements Guardrail, InputGuardrail {

    /**
     * Injection rules, applied over normalized text (lower-cased, zero-width characters stripped,
     * whitespace collapsed — hardened by the T-403 red-team, which evaded the original exact
     * substrings with filler words, casing, and zero-width obfuscation). Variant-tolerant but still
     * heuristic; the residual gaps (paraphrase, encoding, non-English) are documented in
     * docs/security/security-review-2026-07.md.
     */
    private record InjectionRule(String label, Pattern pattern) {
    }

    private static final List<InjectionRule> INJECTION_RULES = List.of(
            rule("instruction-override",
                    "(ignore|disregard|forget|override)\\s+(all\\s+)?(the\\s+|your\\s+|any\\s+)?"
                    + "(previous\\s+|prior\\s+|above\\s+|earlier\\s+|initial\\s+|original\\s+|system\\s+)?"
                    + "(instructions?|prompts?|rules?|directives?)"),
            rule("prompt-exfiltration",
                    "(reveal|show|print|repeat|output|display|leak)\\s+(me\\s+)?(your\\s+|the\\s+)?"
                    + "((system|initial|hidden|original)\\s+prompt|your\\s+instructions)"),
            rule("jailbreak", "developer\\s+mode|do\\s+anything\\s+now|jail\\s?break"),
            rule("guardrail-bypass",
                    "(bypass|ignore|disable|remove|circumvent)\\s+(your\\s+|the\\s+|all\\s+)?"
                    + "(guardrails?|safety\\s+(checks?|filters?|rules?)|content\\s+filters?|restrictions)"));

    private static InjectionRule rule(String label, String regex) {
        return new InjectionRule(label, Pattern.compile(regex));
    }

    /** Zero-width characters used to split trigger words past substring detectors. */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE =
            Pattern.compile("(?:\\+?1[\\-.\\s]?)?\\(?\\d{3}\\)?[\\-.\\s]?\\d{3}[\\-.\\s]?\\d{4}");

    private final PiiMode piiMode;

    public Lc4jInputGuardrail() {
        this(PiiMode.REDACT);
    }

    public Lc4jInputGuardrail(PiiMode piiMode) {
        this.piiMode = Objects.requireNonNull(piiMode, "piiMode");
    }

    /** LangChain4j entry point: heuristic input validation over the user message. */
    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        String injection = detectInjection(text);
        if (injection != null) {
            return failure("prompt-injection: matched \"" + injection + "\"");
        }
        if (piiMode != PiiMode.OFF) {
            Redaction red = redactPii(text);
            if (red.count() > 0) {
                if (piiMode == PiiMode.BLOCK) {
                    return failure("PII detected (" + red.count() + " spans); blocked by policy");
                }
                return successWith(red.text());
            }
        }
        return success();
    }

    /** Our port: run {@link #validate(UserMessage)} and map the LC4j verdict to a {@link GuardrailResult}. */
    @Override
    public GuardrailResult check(GuardrailInput in) {
        Objects.requireNonNull(in, "in");
        if (in.phase() != GuardrailPhase.INPUT) {
            return new GuardrailResult(GuardrailVerdict.PASS, "", null); // output guardrails are a later phase
        }
        try {
            String text = in.text() == null ? "" : in.text();
            InputGuardrailResult result = validate(UserMessage.from(text));
            if (!result.isSuccess()) {
                String message = result.failures().isEmpty()
                        ? "guardrail failure" : result.failures().get(0).message();
                return new GuardrailResult(GuardrailVerdict.FAIL, message, null);
            }
            if (result.hasRewrittenResult()) {
                return new GuardrailResult(GuardrailVerdict.REDACTED, "redacted PII", result.successfulText());
            }
            return new GuardrailResult(GuardrailVerdict.PASS, "", null);
        } catch (RuntimeException e) {
            // Fail closed: never let unchecked input through if the guardrail faults unexpectedly.
            return new GuardrailResult(GuardrailVerdict.FAIL, "guardrail error: " + e.getMessage(), null);
        }
    }

    private static String detectInjection(String text) {
        String normalized = normalize(text);
        for (InjectionRule rule : INJECTION_RULES) {
            Matcher matcher = rule.pattern().matcher(normalized);
            if (matcher.find()) {
                return rule.label() + " \"" + matcher.group() + "\"";
            }
        }
        return null;
    }

    /** Lower-case, strip zero-width characters, collapse whitespace (incl. NBSP) to one space. */
    private static String normalize(String text) {
        String out = ZERO_WIDTH.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        return WHITESPACE.matcher(out).replaceAll(" ");
    }

    private static Redaction redactPii(String text) {
        int[] count = {0};
        String out = mask(text, EMAIL, "[redacted-email]", count);
        out = mask(out, SSN, "[redacted-ssn]", count);
        out = mask(out, PHONE, "[redacted-phone]", count);
        return new Redaction(out, count[0]);
    }

    private static String mask(String text, Pattern pattern, String token, int[] count) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            count[0]++;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private record Redaction(String text, int count) {
    }
}
