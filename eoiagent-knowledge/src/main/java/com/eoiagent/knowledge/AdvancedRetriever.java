package com.eoiagent.knowledge;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.Feature;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link Retriever} decorator that improves recall when {@code ADVANCED_RETRIEVAL} is enabled
 * (rag-knowledge spec §AdvancedRetriever), via three steps:
 *
 * <ol>
 *   <li><strong>rewrite</strong> — the {@link LlmGateway} expands the query into a better search
 *       string (used as the retrieval text);</li>
 *   <li><strong>route</strong> — a deterministic keyword heuristic narrows the corpus to a
 *       {@code sourceType} subset when the intent is clear (and the caller didn't already filter by
 *       sourceType);</li>
 *   <li><strong>over-fetch + re-rank</strong> — fetches {@code k × OVERFETCH} candidates from the
 *       delegate, then re-scores each by its vector score plus lexical overlap with the
 *       <em>original</em> query, returning the top {@code k} ordered by the re-rank score.</li>
 * </ol>
 *
 * <p>Fail-safe: when the feature is disabled it is a pure pass-through to the delegate (no model
 * call, query untouched). Reaches the model only through the {@code LlmGateway} port; read-only.
 */
public final class AdvancedRetriever implements Retriever {

    private static final int OVERFETCH_FACTOR = 4;

    private final Retriever delegate;
    private final LlmGateway gateway;
    private final ConfigProvider config;

    public AdvancedRetriever(Retriever delegate, LlmGateway gateway, ConfigProvider config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        if (!config.featureEnabled(Feature.ADVANCED_RETRIEVAL)) {
            return delegate.retrieve(query); // pass-through when the feature is off
        }

        String rewritten = rewrite(query.text());
        RetrievalQuery expanded = routeAndOverfetch(query, rewritten);
        List<RetrievedChunk> candidates = delegate.retrieve(expanded);
        return reRank(candidates, query.text(), query.k());
    }

    /** Step 1: ask the model to rewrite the query; fall back to the original on an empty reply. */
    private String rewrite(String original) {
        ChatMessageRecord ask = new ChatMessageRecord(ChatRole.USER,
                "Rewrite the following search query to maximize retrieval recall. "
                        + "Reply with only the rewritten query.\n\n" + original,
                Instant.now(), Map.of());
        ChatResult result = gateway.chat(new ChatRequest(List.of(ask), List.of(), ChatOptions.defaults()));
        String text = result.text();
        return (text == null || text.isBlank()) ? original : text.strip();
    }

    /** Steps 2+3 setup: narrow by routed sourceType (if any) and widen k for over-fetch. */
    private RetrievalQuery routeAndOverfetch(RetrievalQuery original, String rewritten) {
        MetadataFilter base = original.filter() == null ? MetadataFilter.none() : original.filter();
        Map<String, String> constraints = new LinkedHashMap<>(base.constraints());
        if (!constraints.containsKey("sourceType")) {
            String routed = route(original.text());
            if (routed != null) {
                constraints.put("sourceType", routed);
            }
        }
        int overfetchK = original.k() * OVERFETCH_FACTOR;
        return new RetrievalQuery(rewritten, overfetchK, new MetadataFilter(constraints));
    }

    /** Step 2: a clear, single-subset keyword signal narrows the corpus; otherwise no narrowing. */
    private static String route(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        boolean schema = containsAny(t, "schema", "table", "column", "dataset");
        boolean pipeline = containsAny(t, "pipeline", "etl", "ingest", " job");
        if (schema && !pipeline) {
            return "SCHEMA_CONFIG";
        }
        if (pipeline && !schema) {
            return "PIPELINE_CONFIG";
        }
        return null; // ambiguous or general → search the whole corpus (don't hurt recall)
    }

    /** Step 3: re-score by vector score + lexical overlap with the original query, top-k by score. */
    private static List<RetrievedChunk> reRank(List<RetrievedChunk> candidates, String originalQuery, int k) {
        Set<String> terms = terms(originalQuery);
        List<RetrievedChunk> rescored = new ArrayList<>(candidates.size());
        for (RetrievedChunk c : candidates) {
            rescored.add(new RetrievedChunk(c.text(), c.score() + lexicalOverlap(c.text(), terms), c.citation()));
        }
        rescored.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return rescored.size() > k ? List.copyOf(rescored.subList(0, k)) : List.copyOf(rescored);
    }

    /** Fraction of the original query's terms present in the chunk (0..1) — a cheap lexical boost. */
    private static double lexicalOverlap(String text, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        long hits = queryTerms.stream().filter(t::contains).count();
        return (double) hits / queryTerms.size();
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text != null) {
            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() > 2) {
                    terms.add(token);
                }
            }
        }
        return terms;
    }

    private static boolean containsAny(String haystack, String... needles) {
        return Arrays.stream(needles).anyMatch(haystack::contains);
    }
}
