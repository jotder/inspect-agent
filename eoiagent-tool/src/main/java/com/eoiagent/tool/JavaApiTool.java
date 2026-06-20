package com.eoiagent.tool;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolExecutionException;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.internal.Json;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps a host {@code @Tool}-annotated Java method as an EOI {@link Tool}. The {@link ToolSpec}'s
 * name, description and JSON schema are derived from the method via LangChain4j's {@code @Tool}
 * introspection ({@link ToolSpecifications#toolSpecificationFrom(Method)}); the classification
 * ({@code mutating}, {@code requiredRole}, {@code capability}) is supplied by the host at
 * registration, since LC4j's {@code @Tool} does not carry it.
 *
 * <p>Phase 1 wraps <strong>read-only</strong> methods; mutating Java-API tools and their approval
 * path arrive in Phase 2.
 */
public final class JavaApiTool implements Tool {

    private final Object host;
    private final Method method;
    private final ToolSpec spec;

    public JavaApiTool(Object host, Method method, boolean mutating, Role requiredRole, Capability capability) {
        this.host = Objects.requireNonNull(host, "host");
        this.method = Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requiredRole, "requiredRole");
        Objects.requireNonNull(capability, "capability");
        ToolSpecification ts = ToolSpecifications.toolSpecificationFrom(method);
        this.spec = new ToolSpec(ts.name(), ts.description(), schemaJson(ts.parameters()),
                mutating, requiredRole, capability);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        Objects.requireNonNull(call, "call");
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(spec.name())
                .arguments(Json.toJson(args))
                .build();
        try {
            String result = new DefaultToolExecutor(host, method).execute(request, call.run());
            return new ToolResult(true, result, null, Map.of());
        } catch (RuntimeException e) {
            // Unexpected fault — expected failures should be modelled as ToolResult{ok=false} upstream.
            throw new ToolExecutionException("tool '" + spec.name() + "' failed to execute", e);
        }
    }

    private static String schemaJson(JsonObjectSchema parameters) {
        if (parameters == null) {
            return "{}"; // no-arg tool
        }
        return Json.toJson(JsonSchemaElementJsonUtils.toMap(parameters));
    }
}
