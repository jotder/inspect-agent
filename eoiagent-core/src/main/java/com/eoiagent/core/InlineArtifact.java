package com.eoiagent.core;

import java.util.Map;

/** An inline binary artifact (e.g. chart, table) returned with an answer. */
public record InlineArtifact(String mimeType, String title, byte[] data, Map<String, Object> meta) {
}
