package com.eoiagent.runtime;

import com.eoiagent.scratchpad.Scratchpad;

import java.util.List;
import java.util.Objects;

/**
 * A {@link Scratchpad} view namespaced under a fixed scope prefix, giving each Flow-D worker its own
 * isolated keyspace (spec §4.2 — "its own Scratchpad scope … workers cannot see each other's
 * scratchpad"). Every key is resolved under {@code scope}; {@link #list} only sees keys under the
 * scope, and a foreign key (one not under this scope) re-prefixes and therefore misses — so a worker
 * can never read another worker's keys through its own view.
 */
final class ScopedScratchpad implements Scratchpad {

    private final Scratchpad delegate;
    private final String scope; // ends with '/'

    ScopedScratchpad(Scratchpad delegate, String scope) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public String write(String key, String content) {
        String full = resolve(key);
        delegate.write(full, content);
        return full; // a globally-addressable handle, still only readable from within this scope
    }

    @Override
    public String read(String key) {
        return delegate.read(resolve(key));
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(scope + Objects.requireNonNull(prefix, "prefix"));
    }

    @Override
    public void delete(String key) {
        delegate.delete(resolve(key));
    }

    /** A handle already under this scope is used as-is; any other key is namespaced under the scope. */
    private String resolve(String key) {
        Objects.requireNonNull(key, "key");
        return key.startsWith(scope) ? key : scope + key;
    }
}
