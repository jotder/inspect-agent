package com.eoiagent.scratchpad;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link Scratchpad}: a {@link ConcurrentMap} of key &rarr; content, discarded at run end.
 * The handle returned from {@link #write} is the key itself. Safe for concurrent writes to distinct
 * keys; same-key writes are last-write-wins. Makes no network or filesystem access — the only
 * scratchpad available in {@code OFFLINE} by construction.
 */
public final class InMemoryScratchpad implements Scratchpad {

    private final ConcurrentMap<String, String> entries = new ConcurrentHashMap<>();

    @Override
    public String write(String key, String content) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(content, "content");
        entries.put(key, content); // overwrites an existing key
        return key;
    }

    @Override
    public String read(String key) {
        Objects.requireNonNull(key, "key");
        String content = entries.get(key);
        if (content == null) {
            throw new ScratchpadKeyNotFound(key);
        }
        return content;
    }

    @Override
    public List<String> list(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return entries.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .toList();
    }

    @Override
    public void delete(String key) {
        Objects.requireNonNull(key, "key");
        entries.remove(key); // idempotent: removing an unknown key is a no-op
    }
}
