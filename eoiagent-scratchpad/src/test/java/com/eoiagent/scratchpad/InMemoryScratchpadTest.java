package com.eoiagent.scratchpad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** InMemoryScratchpad write/read/list/delete semantics (T-109 AC1–AC3, plus overwrite + idempotent delete). */
class InMemoryScratchpadTest {

    @Test
    void writeReturnsHandleAndReadReturnsContent() { // AC1
        InMemoryScratchpad pad = new InMemoryScratchpad();

        String handle = pad.write("run-1/sql/preview", "1842 rows");

        assertThat(handle).isEqualTo("run-1/sql/preview");
        assertThat(pad.read(handle)).isEqualTo("1842 rows");
    }

    @Test
    void readUnknownKeyThrowsScratchpadKeyNotFound() { // AC3 — missing key handled per spec (no null)
        InMemoryScratchpad pad = new InMemoryScratchpad();

        assertThatThrownBy(() -> pad.read("run-1/missing"))
                .isInstanceOf(ScratchpadKeyNotFound.class)
                .isInstanceOf(ScratchpadException.class);
    }

    @Test
    void writeOverwritesExistingKey() {
        InMemoryScratchpad pad = new InMemoryScratchpad();
        pad.write("k", "old");

        pad.write("k", "new");

        assertThat(pad.read("k")).isEqualTo("new");
    }

    @Test
    void listHonorsPrefixSortedAndEmpty() { // AC2
        InMemoryScratchpad pad = new InMemoryScratchpad();
        pad.write("run-1/b", "x");
        pad.write("run-1/a", "x");
        pad.write("run-2/c", "x");

        assertThat(pad.list("run-1/")).containsExactly("run-1/a", "run-1/b");
        assertThat(pad.list("run-9/")).isEmpty();
    }

    @Test
    void deleteRemovesAndIsIdempotent() {
        InMemoryScratchpad pad = new InMemoryScratchpad();
        pad.write("k", "v");

        pad.delete("k");
        assertThatThrownBy(() -> pad.read("k")).isInstanceOf(ScratchpadKeyNotFound.class);

        pad.delete("k"); // idempotent: unknown key is a no-op
        assertThat(pad.list("")).isEmpty();
    }
}
