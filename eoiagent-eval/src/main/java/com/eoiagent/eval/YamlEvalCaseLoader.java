package com.eoiagent.eval;

import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.PageContext;
import com.eoiagent.core.Role;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** Loads an {@link EvalSuite} from the YAML/JSON eval-case schema, validating required fields. */
public final class YamlEvalCaseLoader {

    private YamlEvalCaseLoader() {
    }

    /** Loads a suite by parsing YAML/JSON from the given stream. */
    public static EvalSuite load(InputStream in) {
        Object root = new Yaml().load(in);
        return parse(root);
    }

    /** Loads a suite from a file, delegating to {@link #load(InputStream)}. */
    public static EvalSuite load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new ConfigException("failed to read eval case file: " + path, e);
        }
    }

    /** Loads a suite by parsing the given YAML/JSON string. */
    public static EvalSuite loadString(String yaml) {
        Object root = new Yaml().load(yaml);
        return parse(root);
    }

    private static EvalSuite parse(Object root) {
        Map<String, Object> top = asMap(root, "document root");
        String suite = String.valueOf(top.getOrDefault("suite", ""));
        if (top.get("suite") == null) {
            suite = "";
        }

        Object casesObj = top.get("cases");
        if (!(casesObj instanceof List<?> rawCases) || rawCases.isEmpty()) {
            throw new ConfigException("eval suite must declare a non-empty 'cases' list");
        }

        List<EvalCase> cases = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Object rawCase : rawCases) {
            EvalCase parsed = parseCase(asMap(rawCase, "case"));
            if (!seenIds.add(parsed.id())) {
                throw new ConfigException("duplicate case id: " + parsed.id());
            }
            cases.add(parsed);
        }
        return new EvalSuite(suite, List.copyOf(cases));
    }

    private static EvalCase parseCase(Map<String, Object> c) {
        String id = requireString(c.get("id"), "case is missing required field 'id'");
        String prompt = requireString(c.get("prompt"), "case '" + id + "' is missing required field 'prompt'");
        Role role = parseEnum(Role.class, c.get("role"), Role.USER, "role");
        PageContext page = parsePage(c.get("page"));
        Set<String> tags = parseStringSet(c.get("tags"));

        Object expectObj = c.get("expect");
        if (expectObj == null) {
            throw new ConfigException("case '" + id + "' is missing required field 'expect'");
        }
        Expectation expect = parseExpectation(asMap(expectObj, "expect"), id);
        return new EvalCase(id, prompt, page, role, expect, tags);
    }

    private static Expectation parseExpectation(Map<String, Object> e, String caseId) {
        Object kindObj = e.get("expectedKind");
        if (kindObj == null) {
            throw new ConfigException("case '" + caseId + "' expect is missing required field 'expectedKind'");
        }
        AnswerKind expectedKind = parseEnum(AnswerKind.class, kindObj, null, "expectedKind");

        AnswerAssertion answer = parseAnswerAssertion(e.get("answer"));
        NavigationAssertion navigation = parseNavigationAssertion(e.get("navigation"));
        List<ToolCallAssertion> toolCalls = parseToolCalls(e.get("toolCalls"));
        List<String> mustCite = parseStringList(e.get("mustCiteSourceIds"));

        return new Expectation(expectedKind, answer, toolCalls, navigation, mustCite);
    }

    private static AnswerAssertion parseAnswerAssertion(Object obj) {
        if (obj == null) {
            return null;
        }
        Map<String, Object> a = asMap(obj, "answer");
        MatchMode mode = parseEnum(MatchMode.class, a.get("mode"), MatchMode.EXACT, "mode");
        String expected = a.get("expected") == null ? null : String.valueOf(a.get("expected"));
        double minScore = parseDouble(a.get("minScore"), 0.0);
        return new AnswerAssertion(mode, expected, minScore);
    }

    private static NavigationAssertion parseNavigationAssertion(Object obj) {
        if (obj == null) {
            return null;
        }
        Map<String, Object> n = asMap(obj, "navigation");
        String targetPageId = n.get("targetPageId") == null ? null : String.valueOf(n.get("targetPageId"));
        Map<String, String> requiredParams = parseStringMap(n.get("requiredParams"));
        MatchMode rationaleMode = parseEnum(MatchMode.class, n.get("rationaleMode"), null, "rationaleMode");
        String rationale = n.get("rationale") == null ? null : String.valueOf(n.get("rationale"));
        return new NavigationAssertion(targetPageId, requiredParams, rationaleMode, rationale);
    }

    private static List<ToolCallAssertion> parseToolCalls(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (!(obj instanceof List<?> list)) {
            throw new ConfigException("'toolCalls' must be a list");
        }
        List<ToolCallAssertion> result = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> t = asMap(raw, "toolCall");
            String toolName = t.get("toolName") == null ? null : String.valueOf(t.get("toolName"));
            Map<String, Object> argsSubset = parseObjectMap(t.get("argsSubset"));
            boolean mustBeAbsent = parseBoolean(t.get("mustBeAbsent"), false);
            result.add(new ToolCallAssertion(toolName, argsSubset, mustBeAbsent));
        }
        return List.copyOf(result);
    }

    private static PageContext parsePage(Object obj) {
        if (obj == null) {
            return null;
        }
        Map<String, Object> p = asMap(obj, "page");
        String pageId = p.get("pageId") == null ? "" : String.valueOf(p.get("pageId"));
        Map<String, String> entityIds = parseStringMap(p.get("entityIds"));
        Map<String, String> filters = parseStringMap(p.get("filters"));
        return new PageContext(pageId, entityIds, filters);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object value, E fallback, String field) {
        if (value == null) {
            return fallback;
        }
        String name = String.valueOf(value);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            throw new ConfigException("unknown " + field + " value: " + name);
        }
    }

    private static String requireString(Object value, String message) {
        if (value == null) {
            throw new ConfigException(message);
        }
        return String.valueOf(value);
    }

    private static double parseDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ConfigException("expected a number but got: " + value);
        }
    }

    private static boolean parseBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, String> parseStringMap(Object obj) {
        if (obj == null) {
            return Map.of();
        }
        Map<String, Object> raw = asMap(obj, "string map");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> parseObjectMap(Object obj) {
        if (obj == null) {
            return Map.of();
        }
        Map<String, Object> raw = asMap(obj, "object map");
        return Map.copyOf(raw);
    }

    private static List<String> parseStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (!(obj instanceof List<?> list)) {
            throw new ConfigException("expected a list but got: " + obj);
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(item == null ? null : String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private static Set<String> parseStringSet(Object obj) {
        if (obj == null) {
            return Set.of();
        }
        if (!(obj instanceof List<?> list)) {
            throw new ConfigException("expected a list but got: " + obj);
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            result.add(item == null ? null : String.valueOf(item));
        }
        return Set.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj, String what) {
        if (obj == null) {
            throw new ConfigException("expected a mapping for " + what + " but got null");
        }
        if (!(obj instanceof Map<?, ?>)) {
            throw new ConfigException("expected a mapping for " + what + " but got: " + obj.getClass().getSimpleName());
        }
        return (Map<String, Object>) obj;
    }
}
