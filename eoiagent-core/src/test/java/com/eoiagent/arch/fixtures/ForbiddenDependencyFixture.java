package com.eoiagent.arch.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately depends on a third-party library (AssertJ, {@code org.assertj}) so the architecture
 * scanner has a known-bad class to flag. This is a TEST fixture only — it lives in test-classes and
 * is never scanned by the production rule (which scans {@code target/classes}). It exists to prove
 * the negative case in {@link com.eoiagent.arch.CoreArchitectureTest} (T-005 AC2).
 */
public final class ForbiddenDependencyFixture {

    private ForbiddenDependencyFixture() {
    }

    static boolean usesThirdParty() {
        assertThat(true).isTrue();
        return true;
    }
}
