package com.eoiagent.app;

import com.eoiagent.core.GoalKind;
import java.util.Map;

/**
 * Domain system prompts, persona and glossary for the model. {@code systemPrompt(GoalKind)} is
 * non-null for every {@link GoalKind} (return a sensible default rather than null);
 * {@code domainGlossary()} may be empty.
 */
public interface PromptProfile {

    /** The domain system prompt for the given goal kind. */
    String systemPrompt(GoalKind kind);

    /** The agent persona this pack presents. */
    String persona();

    /** Domain terms the model should know, term to definition. */
    Map<String, String> domainGlossary();
}
