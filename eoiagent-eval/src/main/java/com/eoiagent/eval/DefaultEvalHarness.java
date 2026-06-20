package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Default {@link EvalHarness} that opens a session per case, asks the prompt and scores the answer. */
public final class DefaultEvalHarness implements EvalHarness {

    private final Scorer scorer;

    /** Creates a harness using a {@link CompositeScorer}. */
    public DefaultEvalHarness() {
        this(new CompositeScorer());
    }

    /** Creates a harness using the given scorer. */
    public DefaultEvalHarness(Scorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public EvalReport run(EvalSuite suite, AgentService agent, DeploymentProfile profile) {
        List<CaseOutcome> outcomes = new ArrayList<>();
        for (EvalCase c : suite.cases()) {
            outcomes.add(runCase(c, agent, profile));
        }
        int total = suite.cases().size();
        int passed = 0;
        for (CaseOutcome outcome : outcomes) {
            if (outcome.score().pass()) {
                passed++;
            }
        }
        int failed = total - passed;
        return new EvalReport(suite.name(), profile, total, passed, failed, List.copyOf(outcomes), Instant.now());
    }

    private CaseOutcome runCase(EvalCase c, AgentService agent, DeploymentProfile profile) {
        AgentSession session = null;
        try {
            session = agent.open(new SessionRequest(new UserId("eval"), c.role(), profile, c.page(), Map.of()));
            AgentAnswer answer = session.ask(new UserMessage(c.prompt(), c.page(), Instant.now()));
            EvalRunResult actual = new EvalRunResult(answer, List.of(), List.of(), answer.run());
            Score score = scorer.score(c, actual);
            return new CaseOutcome(c, score, actual);
        } catch (RuntimeException ex) {
            Score score;
            if (c.expect().expectedKind() == AnswerKind.ERROR) {
                score = new Score(true, 1.0, "expected error: " + ex);
            } else {
                score = new Score(false, 0.0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            return new CaseOutcome(c, score, null);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }
}
