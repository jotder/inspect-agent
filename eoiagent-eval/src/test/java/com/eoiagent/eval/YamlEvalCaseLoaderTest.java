package com.eoiagent.eval;

import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Role;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing the documented case schema and rejecting malformed input (T-008 AC1). */
class YamlEvalCaseLoaderTest {

    @Test
    void loadsTheSampleSuiteFromClasspath() { // AC1
        try (InputStream in = getClass().getResourceAsStream("/eval/phase1-smoke/smoke.yaml")) {
            EvalSuite suite = YamlEvalCaseLoader.load(in);

            assertThat(suite.name()).isEqualTo("phase1-smoke");
            assertThat(suite.cases()).hasSize(3);
            assertThat(suite.cases().stream().map(EvalCase::id))
                    .containsExactly("qa-greeting", "qa-exact-ok", "nav-pipeline-failures");

            EvalCase nav = suite.cases().get(2);
            assertThat(nav.role()).isEqualTo(Role.ANALYST);
            assertThat(nav.page().entityIds()).containsEntry("pipelineId", "pl-123");
            assertThat(nav.expect().expectedKind()).isEqualTo(AnswerKind.NAVIGATION);
            assertThat(nav.expect().navigation().targetPageId()).isEqualTo("pipeline-run-history");
            assertThat(nav.expect().navigation().requiredParams())
                    .containsEntry("status", "FAILED");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void defaultsAreAppliedForOptionalFields() {
        EvalSuite suite = YamlEvalCaseLoader.loadString("""
                cases:
                  - id: minimal
                    prompt: "hi"
                    expect:
                      expectedKind: TEXT
                """);
        EvalCase c = suite.cases().get(0);
        assertThat(c.role()).isEqualTo(Role.USER);                 // default
        assertThat(c.tags()).isEmpty();
        assertThat(c.expect().toolCalls()).isEmpty();
        assertThat(c.expect().mustCiteSourceIds()).isEmpty();
        assertThat(c.expect().answer()).isNull();
    }

    @Test
    void rejectsDuplicateCaseIds() { // AC1
        assertThatThrownBy(() -> YamlEvalCaseLoader.loadString("""
                cases:
                  - id: dup
                    prompt: "a"
                    expect: { expectedKind: TEXT }
                  - id: dup
                    prompt: "b"
                    expect: { expectedKind: TEXT }
                """))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void rejectsMissingRequiredFields() { // AC1
        assertThatThrownBy(() -> YamlEvalCaseLoader.loadString("""
                cases:
                  - id: no-prompt
                    expect: { expectedKind: TEXT }
                """))
                .isInstanceOf(ConfigException.class);

        assertThatThrownBy(() -> YamlEvalCaseLoader.loadString("""
                cases:
                  - id: no-expect
                    prompt: "hi"
                """))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void rejectsUnknownEnumValue() { // AC1
        assertThatThrownBy(() -> YamlEvalCaseLoader.loadString("""
                cases:
                  - id: bad-kind
                    prompt: "hi"
                    expect: { expectedKind: TELEPATHY }
                """))
                .isInstanceOf(ConfigException.class);
    }
}
