package com.eoiagent.persistence;

import com.eoiagent.core.RunId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioral contract every {@link CheckpointStore} adapter must satisfy (orchestration-runtime
 * spec §Behavior): append-only {@code save}, {@code latest} = most recent (empty for an unknown run),
 * {@code history} oldest-to-newest, runs isolated, opaque state round-trips. Abstract — run via the
 * concrete subclasses ({@link InMemoryCheckpointStoreTest}, {@link PostgresCheckpointStoreTest}).
 */
abstract class CheckpointStoreContractTest {

    private static final RunId RUN = new RunId("run-1");
    private static final RunId OTHER = new RunId("run-2");

    /** A fresh store backing one test (the Postgres subclass resets its table per test). */
    protected abstract CheckpointStore store();

    private static Checkpoint cp(RunId run, String node, String state, int seq) {
        return new Checkpoint(run, node, state.getBytes(StandardCharsets.UTF_8),
                Instant.now().truncatedTo(ChronoUnit.MICROS), seq);
    }

    @Test
    void saveThenLatestReturnsTheMostRecent() {
        CheckpointStore store = store();
        store.save(RUN, cp(RUN, "gatherSignals", "s0", 0));
        store.save(RUN, cp(RUN, "hypothesize", "s1", 1));

        Optional<Checkpoint> latest = store.latest(RUN);
        assertThat(latest).isPresent();
        assertThat(latest.get().nodeId()).isEqualTo("hypothesize");
        assertThat(latest.get().seq()).isEqualTo(1);
        assertThat(new String(latest.get().state(), StandardCharsets.UTF_8)).isEqualTo("s1");
    }

    @Test
    void historyIsOldestToNewest() {
        CheckpointStore store = store();
        store.save(RUN, cp(RUN, "a", "0", 0));
        store.save(RUN, cp(RUN, "b", "1", 1));
        store.save(RUN, cp(RUN, "c", "2", 2));

        assertThat(store.history(RUN)).extracting(Checkpoint::nodeId).containsExactly("a", "b", "c");
        assertThat(store.history(RUN)).extracting(Checkpoint::seq).containsExactly(0, 1, 2);
    }

    @Test
    void latestIsEmptyForUnknownRun() {
        assertThat(store().latest(new RunId("nope"))).isEmpty();
    }

    @Test
    void historyIsEmptyForUnknownRun() {
        assertThat(store().history(new RunId("nope"))).isEmpty();
    }

    @Test
    void saveIsAppendOnlyAndNeverOverwrites() {
        CheckpointStore store = store();
        store.save(RUN, cp(RUN, "n", "first", 0));
        store.save(RUN, cp(RUN, "n", "second", 0)); // same node/seq — both retained

        assertThat(store.history(RUN)).hasSize(2);
        assertThat(store.history(RUN)).extracting(c -> new String(c.state(), StandardCharsets.UTF_8))
                .containsExactly("first", "second");
    }

    @Test
    void distinctRunsAreIsolated() {
        CheckpointStore store = store();
        store.save(RUN, cp(RUN, "a", "x", 0));
        store.save(OTHER, cp(OTHER, "b", "y", 0));

        assertThat(store.history(RUN)).extracting(Checkpoint::nodeId).containsExactly("a");
        assertThat(store.history(OTHER)).extracting(Checkpoint::nodeId).containsExactly("b");
        assertThat(store.latest(RUN).orElseThrow().nodeId()).isEqualTo("a");
    }

    @Test
    void opaqueStateAndTimestampRoundTrip() {
        CheckpointStore store = store();
        byte[] payload = "{\"hypothesis\":\"disk full\",\"rounds\":2}".getBytes(StandardCharsets.UTF_8);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

        store.save(RUN, new Checkpoint(RUN, "conclude", payload, at, 5));

        Checkpoint got = store.latest(RUN).orElseThrow();
        assertThat(got.state()).isEqualTo(payload); // byte[] content equality
        assertThat(got.at()).isEqualTo(at);
        assertThat(got.seq()).isEqualTo(5);
        assertThat(got.nodeId()).isEqualTo("conclude");
    }
}
