package com.eoiagent.eval;

import com.eoiagent.core.AnswerKind;
import java.util.List;

/** The expected outcome for an eval case: answer kind plus optional answer/tool/nav/citation assertions. */
public record Expectation(AnswerKind expectedKind,
                          AnswerAssertion answer,
                          List<ToolCallAssertion> toolCalls,
                          NavigationAssertion navigation,
                          List<String> mustCiteSourceIds) {
}
