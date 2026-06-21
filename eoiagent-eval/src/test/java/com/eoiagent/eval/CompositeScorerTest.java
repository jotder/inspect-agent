package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic scoring with no model and no network (T-008 AC3 / spec AC3, AC5). */
class CompositeScorerTest {

    private final Scorer scorer = new CompositeScorer();

    private static PageContext page() {
        return new PageContext("home", Map.of(), Map.of());
    }

    private static EvalCase textCase(MatchMode mode, String expected) {
        return new EvalCase("c", "p", page(), Role.USER,
                new Expectation(AnswerKind.TEXT, new AnswerAssertion(mode, expected, 0.0),
                        List.of(), null, List.of()),
                Set.of());
    }

    private static EvalRunResult textResult(String text) {
        AgentAnswer a = new AgentAnswer(AnswerKind.TEXT, text, null, null, List.of(), new RunId("r"));
        return new EvalRunResult(a, List.of(), List.of(), a.run());
    }

    @Test
    void exactMatch() { // AC3
        assertThat(scorer.score(textCase(MatchMode.EXACT, "ok"), textResult("ok")).pass()).isTrue();
        assertThat(scorer.score(textCase(MatchMode.EXACT, "ok"), textResult("nope")).pass()).isFalse();
    }

    @Test
    void containsMatchIsCaseInsensitive() { // AC3
        assertThat(scorer.score(textCase(MatchMode.CONTAINS, "hello"), textResult("Well, HELLO there")).pass()).isTrue();
        assertThat(scorer.score(textCase(MatchMode.CONTAINS, "bye"), textResult("hello there")).pass()).isFalse();
    }

    @Test
    void regexMatch() { // AC3
        assertThat(scorer.score(textCase(MatchMode.REGEX, "step \\d+"), textResult("failed at step 3")).pass()).isTrue();
        assertThat(scorer.score(textCase(MatchMode.REGEX, "^done$"), textResult("not done")).pass()).isFalse();
    }

    @Test
    void kindMismatchFails() {
        AgentAnswer nav = new AgentAnswer(AnswerKind.NAVIGATION, "", null,
                new NavigationIntent("p", Map.of(), ""), List.of(), new RunId("r"));
        EvalRunResult actual = new EvalRunResult(nav, List.of(), List.of(), nav.run());
        assertThat(scorer.score(textCase(MatchMode.EXACT, "ok"), actual).pass()).isFalse();
    }

    @Test
    void navigationPassesOnTargetAndSubsetParams() { // AC5
        EvalCase c = new EvalCase("n", "p", page(), Role.ANALYST,
                new Expectation(AnswerKind.NAVIGATION, null, List.of(),
                        new NavigationAssertion("run-history",
                                Map.of("pipelineId", "pl-1", "status", "FAILED"), null, null),
                        List.of()),
                Set.of());
        AgentAnswer ans = new AgentAnswer(AnswerKind.NAVIGATION, "", null,
                new NavigationIntent("run-history",
                        Map.of("pipelineId", "pl-1", "status", "FAILED", "period", "Q3"), "why"),
                List.of(), new RunId("r"));
        EvalRunResult actual = new EvalRunResult(ans, List.of(), List.of(), ans.run());

        assertThat(scorer.score(c, actual).pass()).isTrue();
    }

    @Test
    void navigationFailsOnWrongTargetOrMissingParam() { // AC5
        EvalCase c = new EvalCase("n", "p", page(), Role.ANALYST,
                new Expectation(AnswerKind.NAVIGATION, null, List.of(),
                        new NavigationAssertion("run-history", Map.of("status", "FAILED"), null, null),
                        List.of()),
                Set.of());
        AgentAnswer wrongTarget = new AgentAnswer(AnswerKind.NAVIGATION, "", null,
                new NavigationIntent("some-other-page", Map.of("status", "FAILED"), ""),
                List.of(), new RunId("r"));
        AgentAnswer missingParam = new AgentAnswer(AnswerKind.NAVIGATION, "", null,
                new NavigationIntent("run-history", Map.of("period", "Q3"), ""),
                List.of(), new RunId("r"));

        assertThat(scorer.score(c, new EvalRunResult(wrongTarget, List.of(), List.of(), wrongTarget.run())).pass()).isFalse();
        assertThat(scorer.score(c, new EvalRunResult(missingParam, List.of(), List.of(), missingParam.run())).pass()).isFalse();
    }

    @Test
    void toolCallPresentWithArgsSubsetPassesAndMissingFails() { // AC4
        EvalCase c = new EvalCase("t", "p", page(), Role.ANALYST,
                new Expectation(AnswerKind.TEXT, null,
                        List.of(new ToolCallAssertion("get_status", Map.of("id", "x1"), false)), null, List.of()),
                Set.of());
        AgentAnswer ans = new AgentAnswer(AnswerKind.TEXT, "ok", null, null, List.of(), new RunId("r"));
        EvalRunResult present = new EvalRunResult(ans,
                List.of(new ToolCall("get_status", Map.of("id", "x1", "extra", "y"), new RunId("r"))),
                List.of(), ans.run());
        EvalRunResult missing = new EvalRunResult(ans, List.of(), List.of(), ans.run());

        assertThat(scorer.score(c, present).pass()).isTrue();
        assertThat(scorer.score(c, missing).pass()).isFalse();
    }

    @Test
    void mutatingToolMustBeAbsentPassesWhenAbsentFailsWhenInvoked() { // AC4
        EvalCase c = new EvalCase("t", "p", page(), Role.ANALYST,
                new Expectation(AnswerKind.TEXT, null,
                        List.of(new ToolCallAssertion("run_pipeline", Map.of(), true)), null, List.of()),
                Set.of());
        AgentAnswer ans = new AgentAnswer(AnswerKind.TEXT, "ok", null, null, List.of(), new RunId("r"));
        EvalRunResult absent = new EvalRunResult(ans, List.of(), List.of(), ans.run());
        EvalRunResult invoked = new EvalRunResult(ans,
                List.of(new ToolCall("run_pipeline", Map.of(), new RunId("r"))), List.of(), ans.run());

        assertThat(scorer.score(c, absent).pass()).isTrue();
        assertThat(scorer.score(c, invoked).pass()).isFalse();
        assertThat(scorer.score(c, invoked).detail()).containsIgnoringCase("must be absent");
    }

    @Test
    void citationsRequiredPassWhenPresentFailWhenMissing() {
        EvalCase c = new EvalCase("t", "p", page(), Role.USER,
                new Expectation(AnswerKind.TEXT, null, List.of(), null, List.of("docs/a", "docs/b")),
                Set.of());
        AgentAnswer ans = new AgentAnswer(AnswerKind.TEXT, "ok", null, null, List.of(), new RunId("r"));
        EvalRunResult present = new EvalRunResult(ans, List.of(), List.of("docs/a", "docs/b", "docs/c"), ans.run());
        EvalRunResult missing = new EvalRunResult(ans, List.of(), List.of("docs/a"), ans.run());

        assertThat(scorer.score(c, present).pass()).isTrue();
        assertThat(scorer.score(c, missing).pass()).isFalse();
    }

    @Test
    void llmJudgeIsUnsupportedInTheDeterministicScorer() {
        Score judgeScore = scorer.score(textCase(MatchMode.LLM_JUDGE, "rubric"), textResult("x"));
        assertThat(judgeScore.pass()).isFalse();
        assertThat(judgeScore.detail()).containsIgnoringCase("LLM_JUDGE");
    }
}
