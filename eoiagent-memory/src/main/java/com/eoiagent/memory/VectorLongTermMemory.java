package com.eoiagent.memory;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.SessionId;
import com.eoiagent.knowledge.EmbeddedChunk;
import com.eoiagent.knowledge.Match;
import com.eoiagent.knowledge.MetadataFilter;
import com.eoiagent.knowledge.VectorStore;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.LlmGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-session {@link LongTermMemory} on top of the RAG stack (Phase 3): {@code remember} embeds
 * the fact via the {@link LlmGateway} embedding model and stores it in a {@link VectorStore};
 * {@code recall} is a top-k vector search mapped back to {@link MemoryFact}s, ranked by relevance
 * with newest-as-tiebreak. Both operations are gated by {@link Feature#LONG_TERM_MEMORY} and fail
 * closed with {@link PolicyViolation} when the profile disables it (memory spec §4, AC8).
 *
 * <p>The fact's scope and timestamp travel in reserved chunk-metadata keys ({@code ltm.scope},
 * {@code ltm.at}) so recall can rebuild the {@link MemoryFact}; user metadata keys are stored
 * alongside and restored verbatim. Chunk ids are UUID strings (a pgvector-backed store requires
 * UUID ids).
 */
public final class VectorLongTermMemory implements LongTermMemory {

    private static final String SCOPE_KEY = "ltm.scope";
    private static final String AT_KEY = "ltm.at";

    private final LlmGateway gateway;
    private final VectorStore store;
    private final ConfigProvider config;

    public VectorLongTermMemory(LlmGateway gateway, VectorStore store, ConfigProvider config) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void remember(SessionId scope, MemoryFact fact) {
        requireEnabled();
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fact, "fact");

        Map<String, String> metadata = new HashMap<>(fact.meta() == null ? Map.of() : fact.meta());
        metadata.put(SCOPE_KEY, scope.value());
        metadata.put(AT_KEY, (fact.at() == null ? Instant.now() : fact.at()).toString());

        float[] vector = embed(fact.text());
        store.add(List.of(new EmbeddedChunk(UUID.randomUUID().toString(), fact.text(), vector, metadata)));
    }

    @Override
    public List<MemoryFact> recall(String query, int k) {
        requireEnabled();
        Objects.requireNonNull(query, "query");
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1, was " + k);
        }

        List<Match> matches = store.search(embed(query), k, MetadataFilter.none());
        List<Match> ranked = new ArrayList<>(matches);
        ranked.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(m -> atOf(m.chunk()), Comparator.reverseOrder()));

        List<MemoryFact> facts = new ArrayList<>(ranked.size());
        for (Match m : ranked) {
            facts.add(toFact(m.chunk()));
        }
        return facts;
    }

    private void requireEnabled() {
        if (!config.featureEnabled(Feature.LONG_TERM_MEMORY)) {
            throw new PolicyViolation("long-term memory requires the LONG_TERM_MEMORY feature, "
                    + "which is not enabled for profile " + config.profile());
        }
    }

    private float[] embed(String text) {
        return gateway.embed(new EmbeddingRequest(List.of(text))).vectors().get(0);
    }

    private static MemoryFact toFact(EmbeddedChunk chunk) {
        Map<String, String> meta = new HashMap<>(chunk.metadata());
        String scope = meta.remove(SCOPE_KEY);
        meta.remove(AT_KEY);
        return new MemoryFact(new SessionId(scope), chunk.text(), atOf(chunk), Map.copyOf(meta));
    }

    private static Instant atOf(EmbeddedChunk chunk) {
        String at = chunk.metadata().get(AT_KEY);
        return at == null ? Instant.EPOCH : Instant.parse(at);
    }
}
