package com.eoiagent.core;

/** A guardrail verdict with an explanatory message and any transformed text. */
public record GuardrailResult(GuardrailVerdict verdict, String message, String transformedText) {
}
