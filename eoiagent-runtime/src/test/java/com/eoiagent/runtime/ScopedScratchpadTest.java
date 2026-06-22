package com.eoiagent.runtime;

import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.scratchpad.ScratchpadKeyNotFound;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ScopedScratchpad isolates each worker's keyspace: a worker cannot read another's keys (T-205 AC7). */
class ScopedScratchpadTest {

    @Test
    void workersCannotSeeOrReadEachOthersKeys() {
        InMemoryScratchpad real = new InMemoryScratchpad();
        ScopedScratchpad analysis = new ScopedScratchpad(real, "run1/worker/analysis/");
        ScopedScratchpad sql = new ScopedScratchpad(real, "run1/worker/sql/");

        String analysisHandle = analysis.write("finding", "schema secret");
        sql.write("note", "sql note");

        // Each view lists only its own keys.
        assertThat(analysis.list("")).containsExactly("run1/worker/analysis/finding");
        assertThat(sql.list("")).containsExactly("run1/worker/sql/note");

        // The sql worker cannot read the analysis worker's key — by logical name or by its full handle.
        assertThatThrownBy(() -> sql.read("finding")).isInstanceOf(ScratchpadKeyNotFound.class);
        assertThatThrownBy(() -> sql.read(analysisHandle)).isInstanceOf(ScratchpadKeyNotFound.class);

        // The owner can read its own key by logical name and by the returned handle.
        assertThat(analysis.read("finding")).isEqualTo("schema secret");
        assertThat(analysis.read(analysisHandle)).isEqualTo("schema secret");

        // The underlying store holds both, namespaced distinctly.
        assertThat(real.list("")).containsExactlyInAnyOrder(
                "run1/worker/analysis/finding", "run1/worker/sql/note");
    }

    @Test
    void deleteIsScopedToTheWorker() {
        InMemoryScratchpad real = new InMemoryScratchpad();
        ScopedScratchpad analysis = new ScopedScratchpad(real, "run1/worker/analysis/");
        ScopedScratchpad sql = new ScopedScratchpad(real, "run1/worker/sql/");
        analysis.write("k", "v");
        sql.write("k", "v");

        sql.delete("k"); // deletes only sql's key

        assertThat(sql.list("")).isEmpty();
        assertThat(analysis.read("k")).isEqualTo("v");
    }
}
