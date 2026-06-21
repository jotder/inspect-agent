package com.eoiagent.eval;

import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A deterministic {@link Scorer} that runs ordered kind/answer/navigation/tool/citation checks. */
public final class CompositeScorer implements Scorer {

    /** Creates a scorer with no configuration. */
    public CompositeScorer() {
    }

    @Override
    public Score score(EvalCase expected, EvalRunResult actual) {
        Expectation expect = expected.expect();
        int run = 0;
        int passed = 0;
        String firstFailure = null;

        // KIND
        run++;
        AnswerKind got = actual.answer() == null ? null : actual.answer().kind();
        if (got == expect.expectedKind()) {
            passed++;
        } else {
            firstFailure = "kind: expected " + expect.expectedKind() + " got " + got;
        }

        // ANSWER
        if (expect.answer() != null) {
            run++;
            String detail = checkAnswer(expect.answer(), actual);
            if (detail == null) {
                passed++;
            } else if (firstFailure == null) {
                firstFailure = detail;
            }
        }

        // NAVIGATION
        if (expect.navigation() != null) {
            run++;
            String detail = checkNavigation(expect.navigation(), actual);
            if (detail == null) {
                passed++;
            } else if (firstFailure == null) {
                firstFailure = detail;
            }
        }

        // TOOL CALLS (reconstructed from the audit TOOL_CALL stream)
        if (!expect.toolCalls().isEmpty()) {
            run++;
            String detail = checkToolCalls(expect.toolCalls(), actual);
            if (detail == null) {
                passed++;
            } else if (firstFailure == null) {
                firstFailure = detail;
            }
        }

        // CITATIONS (reconstructed from the audit RETRIEVAL stream)
        if (!expect.mustCiteSourceIds().isEmpty()) {
            run++;
            String detail = checkCitations(expect.mustCiteSourceIds(), actual);
            if (detail == null) {
                passed++;
            } else if (firstFailure == null) {
                firstFailure = detail;
            }
        }

        boolean pass = passed == run;
        double value = run == 0 ? 0.0 : (double) passed / run;
        String detail = firstFailure == null ? "ok" : firstFailure;
        return new Score(pass, value, detail);
    }

    private static String checkAnswer(AnswerAssertion assertion, EvalRunResult actual) {
        String text = actual.answer() == null || actual.answer().text() == null ? "" : actual.answer().text();
        switch (assertion.mode()) {
            case EXACT -> {
                if (assertion.expected().trim().equals(text.trim())) {
                    return null;
                }
                return "answer: expected exact '" + assertion.expected() + "' got '" + text + "'";
            }
            case CONTAINS -> {
                if (text.toLowerCase(Locale.ROOT).contains(assertion.expected().toLowerCase(Locale.ROOT))) {
                    return null;
                }
                return "answer: expected to contain '" + assertion.expected() + "'";
            }
            case REGEX -> {
                if (Pattern.compile(assertion.expected()).matcher(text).find()) {
                    return null;
                }
                return "answer: expected to match regex '" + assertion.expected() + "'";
            }
            case LLM_JUDGE -> {
                return "LLM_JUDGE not supported in scaffold";
            }
            default -> {
                return "answer: unknown match mode " + assertion.mode();
            }
        }
    }

    /**
     * A tool-call assertion holds when a {@code mustBeAbsent} tool was never invoked, or a required
     * tool was invoked with the expected args subset. Reads {@link EvalRunResult#toolCalls()}, which
     * the harness reconstructs from the audit {@code TOOL_CALL} stream — the same record compliance
     * sees, not an internal hook.
     */
    private static String checkToolCalls(List<ToolCallAssertion> assertions, EvalRunResult actual) {
        List<ToolCall> calls = actual.toolCalls() == null ? List.of() : actual.toolCalls();
        for (ToolCallAssertion assertion : assertions) {
            boolean present = calls.stream().anyMatch(c -> Objects.equals(c.toolName(), assertion.toolName()));
            if (assertion.mustBeAbsent()) {
                if (present) {
                    return "tool-call: '" + assertion.toolName() + "' must be absent but was invoked";
                }
            } else {
                boolean matched = calls.stream().anyMatch(c -> Objects.equals(c.toolName(), assertion.toolName())
                        && argsContain(c.arguments(), assertion.argsSubset()));
                if (!matched) {
                    return "tool-call: expected '" + assertion.toolName() + "'"
                            + (assertion.argsSubset().isEmpty() ? "" : " with args " + assertion.argsSubset())
                            + " but it was not invoked";
                }
            }
        }
        return null;
    }

    /** Subset match: every expected entry must be present and equal in the actual arguments. */
    private static boolean argsContain(Map<String, Object> actual, Map<String, Object> expectedSubset) {
        Map<String, Object> args = actual == null ? Map.of() : actual;
        for (Map.Entry<String, Object> entry : expectedSubset.entrySet()) {
            if (!args.containsKey(entry.getKey()) || !Objects.equals(entry.getValue(), args.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Every required source id must appear in {@link EvalRunResult#citedSourceIds()} (audit RETRIEVAL). */
    private static String checkCitations(List<String> mustCite, EvalRunResult actual) {
        List<String> cited = actual.citedSourceIds() == null ? List.of() : actual.citedSourceIds();
        List<String> missing = new ArrayList<>();
        for (String id : mustCite) {
            if (!cited.contains(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            return "citations: missing required source ids " + missing;
        }
        return null;
    }

    private static String checkNavigation(NavigationAssertion assertion, EvalRunResult actual) {
        NavigationIntent nav = actual.answer() == null ? null : actual.answer().navigation();
        if (nav == null) {
            return "navigation: expected a navigation intent but answer had none";
        }
        if (!java.util.Objects.equals(assertion.targetPageId(), nav.targetPageId())) {
            return "navigation: expected targetPageId '" + assertion.targetPageId() + "' got '" + nav.targetPageId() + "'";
        }
        Map<String, String> actualParams = nav.parameters() == null ? Map.of() : nav.parameters();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : assertion.requiredParams().entrySet()) {
            if (!actualParams.containsKey(entry.getKey())
                    || !java.util.Objects.equals(entry.getValue(), actualParams.get(entry.getKey()))) {
                missing.add(entry.getKey());
            }
        }
        if (!missing.isEmpty()) {
            return "navigation: missing/mismatched required params " + missing;
        }
        return null;
    }
}
