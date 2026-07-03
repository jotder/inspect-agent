package com.eoiagent.platform;

import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.ParamSpec;
import com.eoiagent.core.Capability;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The reserved {@value NavigationIntent#TOOL_NAME} tool (T-353), derived from the pack's
 * {@link NavigationCatalog}. The model proposes navigation exactly like any other tool call; this
 * tool validates the proposal against the catalog (unknown page / missing required params fail as
 * an ordinary tool error the model can correct) and returns the canonical intent map the
 * orchestrator turns into a terminal {@code NAVIGATION} answer. Non-mutating — the HOST performs
 * the actual routing.
 */
final class NavigationTool implements Tool {

    private final NavigationCatalog catalog;

    NavigationTool(NavigationCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public ToolSpec spec() {
        StringBuilder description = new StringBuilder(
                "Navigate the user to a product page. Use when the user asks WHERE to find or see "
                        + "something. Available pages:");
        for (PageDescriptor page : catalog.pages()) {
            description.append(" [").append(page.pageId()).append("] ").append(page.title())
                    .append(" - ").append(page.description());
            if (!page.params().isEmpty()) {
                description.append(" (params:");
                for (ParamSpec param : page.params()) {
                    description.append(' ').append(param.name())
                            .append(param.required() ? "*" : "").append(" - ").append(param.description());
                }
                description.append(')');
            }
            description.append(';');
        }
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"pageId\":{\"type\":\"string\",\"description\":\"id of the target page\"},"
                + "\"params\":{\"type\":\"object\",\"description\":\"page parameters by name\"},"
                + "\"rationale\":{\"type\":\"string\",\"description\":\"one sentence: why this page\"}},"
                + "\"required\":[\"pageId\"]}";
        return new ToolSpec(NavigationIntent.TOOL_NAME, description.toString(), schema,
                false, Role.USER, Capability.READ_DOCS);
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        Object rawPageId = call.arguments() == null ? null : call.arguments().get("pageId");
        String pageId = rawPageId == null ? null : String.valueOf(rawPageId);
        if (pageId == null || pageId.isBlank()) {
            return new ToolResult(false, null, "missing required argument 'pageId'", Map.of());
        }
        PageDescriptor page = catalog.find(pageId).orElse(null);
        if (page == null) {
            return new ToolResult(false, null, "unknown page '" + pageId + "' — see the tool "
                    + "description for the available pages", Map.of());
        }

        Map<String, String> params = new LinkedHashMap<>();
        Object rawParams = call.arguments().get("params");
        if (rawParams instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        for (ParamSpec spec : page.params()) {
            if (spec.required() && !params.containsKey(spec.name())) {
                return new ToolResult(false, null, "page '" + pageId + "' requires parameter '"
                        + spec.name() + "' (" + spec.description() + ")", Map.of());
            }
        }

        Object rationale = call.arguments().get("rationale");
        Map<String, Object> intent = new HashMap<>();
        intent.put("targetPageId", page.pageId());
        intent.put("parameters", Map.copyOf(params));
        if (rationale != null) {
            intent.put("rationale", String.valueOf(rationale));
        }
        return new ToolResult(true, intent, null, Map.of());
    }
}
