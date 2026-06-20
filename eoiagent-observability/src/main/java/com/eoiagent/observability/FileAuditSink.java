package com.eoiagent.observability;

import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditException;
import com.eoiagent.core.ConfigException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only {@link AuditSink}: writes each {@link AuditEvent} as one newline-delimited JSON record
 * using {@link StandardOpenOption#APPEND}. It never seeks or rewrites, exposes no update/delete path,
 * and is thread-safe (serialized writes). On a hard I/O failure it throws {@link AuditException} so a
 * mutating caller can fail closed. Pure JDK — the OFFLINE-friendly default audit store.
 */
public final class FileAuditSink implements AuditSink {

    private final Path file;
    private final Object lock = new Object();

    public FileAuditSink(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new ConfigException("audit file directory not writable: " + parent, e);
            }
        }
    }

    @Override
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        String line = toJson(event) + "\n";
        synchronized (lock) {
            try {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new AuditException("failed to append audit event to " + file, e);
            }
        }
    }

    private static String toJson(AuditEvent e) {
        StringBuilder sb = new StringBuilder(192).append('{');
        field(sb, "at", e.at() == null ? null : e.at().toString()).append(',');
        field(sb, "app", e.app() == null ? null : e.app().value()).append(',');
        field(sb, "run", e.run() == null ? null : e.run().value()).append(',');
        field(sb, "session", e.session() == null ? null : e.session().value()).append(',');
        field(sb, "user", e.user() == null ? null : e.user().value()).append(',');
        field(sb, "kind", e.kind() == null ? null : e.kind().name()).append(',');
        field(sb, "summary", e.summary()).append(',');
        sb.append("\"details\":");
        details(sb, e.details());
        return sb.append('}').toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return sb;
    }

    private static void details(StringBuilder sb, Map<String, Object> details) {
        sb.append('{');
        if (details != null) {
            boolean first = true;
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(entry.getKey())).append("\":");
                Object v = entry.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(escape(v.toString())).append('"');
                }
            }
        }
        sb.append('}');
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
