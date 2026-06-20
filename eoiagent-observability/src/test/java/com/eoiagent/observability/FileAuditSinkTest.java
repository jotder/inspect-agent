package com.eoiagent.observability;

import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FileAuditSink persists every field, captures a run's event kinds, and only ever appends (T-113 AC1, AC2). */
class FileAuditSinkTest {

    private static AuditEvent event(AuditKind kind, String summary) {
        return new AuditEvent(Instant.EPOCH, new AppId("app"), new RunId("r1"),
                new SessionId("s1"), new UserId("u1"), kind, summary, Map.of("tool", "echo"));
    }

    @Test
    void persistsAllFieldsAsJsonLine(@TempDir Path dir) throws IOException { // AC1
        Path file = dir.resolve("audit.ndjson");
        new FileAuditSink(file).record(event(AuditKind.TOOL_CALL, "ran echo"));

        String line = Files.readString(file).strip();
        assertThat(line)
                .contains("\"kind\":\"TOOL_CALL\"")
                .contains("\"run\":\"r1\"")
                .contains("\"session\":\"s1\"")
                .contains("\"user\":\"u1\"")
                .contains("\"app\":\"app\"")
                .contains("\"summary\":\"ran echo\"")
                .contains("\"tool\":\"echo\"");
    }

    @Test
    void capturesAllEventKindsOfARunInOrder(@TempDir Path dir) throws IOException { // AC1 (run event stream)
        Path file = dir.resolve("audit.ndjson");
        FileAuditSink sink = new FileAuditSink(file);

        // The kinds a Flow A/B run emits, in order.
        sink.record(event(AuditKind.MODEL_CALL, "turn 1"));
        sink.record(event(AuditKind.RETRIEVAL, "retrieve k=4"));
        sink.record(event(AuditKind.TOOL_CALL, "echo"));
        sink.record(event(AuditKind.DECISION, "final answer"));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("MODEL_CALL");
        assertThat(lines.get(1)).contains("RETRIEVAL");
        assertThat(lines.get(2)).contains("TOOL_CALL");
        assertThat(lines.get(3)).contains("DECISION");
    }

    @Test
    void isAppendOnlyAndPreservesPriorContent(@TempDir Path dir) throws IOException { // AC2
        Path file = dir.resolve("audit.ndjson");
        FileAuditSink sink = new FileAuditSink(file);
        sink.record(event(AuditKind.MODEL_CALL, "first"));
        sink.record(event(AuditKind.TOOL_CALL, "second"));
        String afterTwo = Files.readString(file);

        sink.record(event(AuditKind.DECISION, "third"));
        String afterThree = Files.readString(file);

        assertThat(afterThree).startsWith(afterTwo);              // earlier bytes unchanged
        assertThat(Files.readAllLines(file)).hasSize(3);          // appended, not rewritten
        assertThat(afterThree.indexOf("first")).isLessThan(afterThree.indexOf("third")); // order preserved
    }
}
