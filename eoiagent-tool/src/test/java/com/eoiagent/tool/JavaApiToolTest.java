package com.eoiagent.tool;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JavaApiTool derives its {@link ToolSpec} (schema + name/description) from a host {@code @Tool}
 * method and carries the host-supplied classification, and invokes the method (T-110; spec AC8).
 * The {@code @Tool} annotation is fully qualified to avoid clashing with our own {@code Tool} port.
 */
class JavaApiToolTest {

    /** A host service exposing a read-only @Tool method. */
    static class CatalogApi {
        @dev.langchain4j.agent.tool.Tool("Look up the row count of a dataset")
        long rowCount(@P("dataset") String dataset) {
            return "orders".equals(dataset) ? 1842 : 0;
        }
    }

    private static Method rowCount() throws NoSuchMethodException {
        return CatalogApi.class.getDeclaredMethod("rowCount", String.class);
    }

    @Test
    void specDerivesSchemaAndCarriesHostClassification() throws Exception { // spec AC8
        JavaApiTool tool = new JavaApiTool(new CatalogApi(), rowCount(),
                false, Role.ANALYST, Capability.READ_METADATA);

        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("rowCount");
        assertThat(spec.description()).contains("row count");
        assertThat(spec.jsonSchema()).contains("dataset");
        assertThat(spec.mutating()).isFalse();
        assertThat(spec.requiredRole()).isEqualTo(Role.ANALYST);
        assertThat(spec.capability()).isEqualTo(Capability.READ_METADATA);
    }

    @Test
    void invokeExecutesTheHostMethod() throws Exception {
        JavaApiTool tool = new JavaApiTool(new CatalogApi(), rowCount(),
                false, Role.ANALYST, Capability.READ_METADATA);

        ToolResult result = tool.invoke(new ToolCall("rowCount", Map.of("dataset", "orders"), new RunId("r1")));

        assertThat(result.ok()).isTrue();
        assertThat(String.valueOf(result.value())).contains("1842");
    }
}
