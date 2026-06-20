package com.eoiagent.core;

import java.util.List;

/** A complete agent answer with optional artifact, navigation and citations. */
public record AgentAnswer(AnswerKind kind,
                          String text,
                          InlineArtifact artifact,
                          NavigationIntent navigation,
                          List<Citation> citations,
                          RunId run) {
}
