package com.eoiagent.eval;

import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.NavigationIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        // TOOL CALLS
        if (!expect.toolCalls().isEmpty()) {
            run++;
            if (firstFailure == null) {
                firstFailure = "tool-call assertions not supported in scaffold (no audit stream yet)";
            }
        }

        // CITATIONS
        if (!expect.mustCiteSourceIds().isEmpty()) {
            run++;
            if (firstFailure == null) {
                firstFailure = "citation assertions not supported in scaffold";
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
