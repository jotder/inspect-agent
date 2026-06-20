package com.eoiagent.arch;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads compiled {@code .class} bytes with the JDK Class-File API ({@code java.lang.classfile},
 * JEP 484) and reports the types they reference. Used by {@link CoreArchitectureTest} to enforce
 * the dependency-direction rules (ADR-0004 / ADR-0010) without a third-party bytecode library —
 * which matters because the modules target {@code --release 25} (class-file v69).
 */
final class ClassDependencyScanner {

    /** Packages a CORE class is allowed to reference. Everything else is a third-party leak. */
    static final List<String> ALLOWED_PREFIXES = List.of("java/", "javax/", "jdk/", "com/eoiagent/");

    /** Agent frameworks that must never appear in CORE (ADR-0010). */
    static final List<String> FORBIDDEN_FRAGMENTS = List.of("dev/langchain4j", "org/bsc/langgraph4j");

    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([^;<]+)[;<]");

    private ClassDependencyScanner() {
    }

    /** All raw UTF-8 constant-pool strings (descriptors, signatures, names). */
    static Set<String> utf8Strings(byte[] classBytes) {
        ClassModel model = ClassFile.of().parse(classBytes);
        Set<String> out = new LinkedHashSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof Utf8Entry u) {
                out.add(u.stringValue());
            }
        }
        return out;
    }

    /** Internal names of every type the class references (class entries + descriptor/signature types). */
    static Set<String> referencedTypes(byte[] classBytes) {
        ClassModel model = ClassFile.of().parse(classBytes);
        Set<String> out = new LinkedHashSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry c) {
                out.add(stripArray(c.asInternalName()));
            } else if (entry instanceof Utf8Entry u) {
                Matcher m = DESCRIPTOR_TYPE.matcher(u.stringValue());
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** True if any UTF-8 string mentions a forbidden framework — robust against generic signatures. */
    static boolean referencesForbiddenFramework(Set<String> utf8Strings) {
        return utf8Strings.stream()
                .anyMatch(s -> FORBIDDEN_FRAGMENTS.stream().anyMatch(s::contains));
    }

    /** Referenced types that are neither JDK nor {@code com.eoiagent} (i.e. third-party leaks). */
    static List<String> thirdPartyReferences(Set<String> referencedTypes) {
        return referencedTypes.stream()
                .filter(name -> ALLOWED_PREFIXES.stream().noneMatch(name::startsWith))
                .sorted()
                .toList();
    }

    private static String stripArray(String internalName) {
        int i = 0;
        while (i < internalName.length() && internalName.charAt(i) == '[') {
            i++;
        }
        String s = internalName.substring(i);
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
