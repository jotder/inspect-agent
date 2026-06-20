package com.eoiagent.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ranking, metadata filtering, source removal, and disk round-trip (T-102 AC1–AC3). */
class InMemoryVectorStoreTest {

    private static EmbeddedChunk chunk(String id, String text, float[] v, Map<String, String> md) {
        return new EmbeddedChunk(id, text, v, md);
    }

    private static InMemoryVectorStore stocked() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(
                chunk("a", "alpha", new float[]{1, 0, 0}, Map.of("sourceId", "s1", "sourceType", "PRODUCT_DOC")),
                chunk("b", "beta", new float[]{0, 1, 0}, Map.of("sourceId", "s1", "sourceType", "PRODUCT_DOC")),
                chunk("c", "gamma", new float[]{0.9f, 0.1f, 0}, Map.of("sourceId", "s2", "sourceType", "SCHEMA_CONFIG"))));
        return store;
    }

    @Test
    void searchRanksByDescendingSimilarity() { // AC1
        List<Match> matches = stocked().search(new float[]{1, 0, 0}, 3, MetadataFilter.none());

        assertThat(matches).hasSize(3);
        assertThat(matches.get(0).chunk().id()).isEqualTo("a"); // closest to query
        assertThat(matches).extracting(Match::score).isSortedAccordingTo((x, y) -> Double.compare(y, x));
        assertThat(matches.get(0).chunk().metadata()).containsEntry("sourceType", "PRODUCT_DOC");
    }

    @Test
    void respectsMaxResults() {
        assertThat(stocked().search(new float[]{1, 0, 0}, 1, MetadataFilter.none())).hasSize(1);
    }

    @Test
    void metadataFilterNarrowsResults() { // AC2
        List<Match> matches = stocked().search(
                new float[]{1, 0, 0}, 5, new MetadataFilter(Map.of("sourceType", "SCHEMA_CONFIG")));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).chunk().id()).isEqualTo("c");
    }

    @Test
    void removeBySourceIdDropsOnlyThatSource() {
        InMemoryVectorStore store = stocked();
        store.removeBySourceId("s1");

        List<Match> matches = store.search(new float[]{1, 0, 0}, 5, MetadataFilter.none());
        assertThat(matches).extracting(m -> m.chunk().id()).containsExactly("c");
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path tmp) { // AC3
        Path file = tmp.resolve("index.json");
        stocked().save(file);

        InMemoryVectorStore reloaded = InMemoryVectorStore.load(file);
        List<Match> matches = reloaded.search(new float[]{1, 0, 0}, 3, MetadataFilter.none());

        assertThat(matches).hasSize(3);
        assertThat(matches.get(0).chunk().id()).isEqualTo("a");
        assertThat(matches.get(0).chunk().metadata()).containsEntry("sourceId", "s1");
    }
}
