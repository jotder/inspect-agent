package com.eoiagent.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the CORE dependency rules from conventions §2, ADR-0004 (hexagonal) and ADR-0010
 * (experimental deps isolated to adapters): nothing compiled into {@code eoiagent-core} may import
 * an agent framework or any third-party library — CORE is JDK-only ports + domain types.
 *
 * <p>Implemented with the JDK Class-File API instead of ArchUnit: the modules target
 * {@code --release 25} (class-file v69), which ArchUnit's bundled ASM does not yet read, and the
 * native API keeps CORE free of test-time third-party bytecode tooling.
 */
class CoreArchitectureTest {

    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    @Test
    void coreHasNoAgentFrameworkOnAnyClass() { // ADR-0010
        forEachMainClass((path, bytes) -> {
            boolean leaks = ClassDependencyScanner.referencesForbiddenFramework(
                    ClassDependencyScanner.utf8Strings(bytes));
            assertThat(leaks)
                    .as("agent-framework reference in %s", path)
                    .isFalse();
        });
    }

    @Test
    void coreDependsOnlyOnJdkAndItself() { // conventions §2 / §1 (framework-free foundation)
        forEachMainClass((path, bytes) -> {
            List<String> thirdParty = ClassDependencyScanner.thirdPartyReferences(
                    ClassDependencyScanner.referencedTypes(bytes));
            assertThat(thirdParty)
                    .as("third-party references in %s", path)
                    .isEmpty();
        });
    }

    @Test
    void scannerCatchesAFrameworkLeakHiddenInAGenericSignature() { // AC2 (negative)
        // A generic field/return type such as List<dev.langchain4j.model.chat.ChatModel>.
        boolean detected = ClassDependencyScanner.referencesForbiddenFramework(
                java.util.Set.of("Ljava/util/List<Ldev/langchain4j/model/chat/ChatModel;>;"));
        assertThat(detected).isTrue();
    }

    @Test
    void scannerFlagsACompiledClassWithAThirdPartyDependency() { // AC2 (negative, real class)
        Path fixture = Path.of("target", "test-classes",
                "com", "eoiagent", "arch", "fixtures", "ForbiddenDependencyFixture.class");
        assertThat(fixture).as("fixture must be compiled").exists();

        byte[] bytes = readAll(fixture);
        List<String> thirdParty =
                ClassDependencyScanner.thirdPartyReferences(ClassDependencyScanner.referencedTypes(bytes));

        assertThat(thirdParty)
                .as("scanner must flag the AssertJ dependency the fixture deliberately uses")
                .anyMatch(name -> name.startsWith("org/assertj/"));
    }

    // --- helpers ------------------------------------------------------------------------------

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
            assertThat(classes).as("CORE should have compiled classes to scan").isNotEmpty();
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
