package com.eoiagent.safety;

import com.eoiagent.core.AgentContext;

/** Input to a guardrail check: phase, text, expected schema and context. */
public record GuardrailInput(GuardrailPhase phase, String text, String expectedJsonSchema, AgentContext ctx) {
}
