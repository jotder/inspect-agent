package com.eoiagent.model;

import java.util.List;

/**
 * Tunable options for a chat completion request.
 * Fields provisional — refined by the owning module spec.
 */
public record ChatOptions(Double temperature, Integer maxOutputTokens, Double topP, List<String> stop) {

    public static ChatOptions defaults() {
        return new ChatOptions(null, null, null, List.of());
    }
}
