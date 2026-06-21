package com.eoiagent.app.reference.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the reference pack's dependency-direction rule (T-116 AC8; ADR-0011): every class compiled
 * into {@code eoiagent-app-reference} references only JDK + {@code com.eoiagent} types (so it cannot
 * reach a core adapter module's third-party deps) and never an agent framework. The pack depends on
 * {@code eoiagent-app-api} + the BOM only — that the pom holds is enforced at compile time; this test
 * guards the bytecode.
 *
 * <p>Uses the JDK Class-File API rather than ArchUnit because the modules target {@code --release 25}
 * (class-file v69), which ArchUnit's bundled ASM does not yet read.
 */
class ReferencePackDependencyRulesTest {

    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    @Test
    void referencePackHasNoAgentFrameworkOnAnyClass() {
        forEachMainClass((path, bytes) -> {
            boolean leaks = DependencyScanner.referencesForbiddenFramework(DependencyScanner.utf8Strings(bytes));
            assertThat(leaks).as("agent-framework reference in %s", path).isFalse();
        });
    }

    @Test
    void referencePackDependsOnlyOnJdkAndComEoiagent() {
        forEachMainClass((path, bytes) -> {
            List<String> thirdParty = DependencyScanner.thirdPartyReferences(DependencyScanner.referencedTypes(bytes));
            assertThat(thirdParty).as("third-party references in %s", path).isEmpty();
        });
    }

    @Test
    void scannerCatchesAFrameworkLeakHiddenInAGenericSignature() {
        boolean detected = DependencyScanner.referencesForbiddenFramework(
                java.util.Set.of("Ljava/util/List<Ldev/langchain4j/model/chat/ChatModel;>;"));
        assertThat(detected).isTrue();
    }

    @FunctionalInterface
    private interface ClassCheck {
        void check(Path path, byte[] bytes);
    }

    private static void forEachMainClass(ClassCheck check) {
        assertThat(MAIN_CLASSES)
                .as("compiled main classes must exist before the architecture test runs")
                .exists();
        try (Stream<Path> paths = Files.walk(MAIN_CLASSES)) {
            List<Path> classes = paths
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .toList();
            assertThat(classes).as("reference pack should have compiled classes to scan").isNotEmpty();
            for (Path p : classes) {
                check.check(p, readAll(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
