package com.eoiagent.examples;

import com.eoiagent.core.Citation;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import com.eoiagent.knowledge.AdvancedRetriever;
import com.eoiagent.knowledge.Retriever;
import com.eoiagent.model.StubLlmGateway;

import java.util.List;

/**
 * Phase-2 — advanced retrieval (T-208). {@link AdvancedRetriever} decorates any {@link Retriever}: when
 * {@code ADVANCED_RETRIEVAL} is enabled it rewrites the query (via the LLM), routes to a corpus subset
 * by keyword, over-fetches {@code k×4} candidates, then re-ranks by vector score + lexical overlap with
 * the <em>original</em> query. When disabled it is a pure pass-through.
 *
 * <p>The contrast is the point: a naive top-2 vector search surfaces the two highest-scoring (but
 * off-topic) chunks; advanced retrieval widens the candidate pool and the lexical re-rank promotes the
 * genuinely relevant schema doc that vector score alone buried.
 */
public final class AdvancedRetrievalDemo {

    private static final String QUERY = "orders table schema columns";

    private AdvancedRetrievalDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Advanced retrieval: rewrite + route + re-rank (vs naive vector search)");

        Retriever corpus = new FixedCorpus();
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("orders fact table schema column definitions and keys")
                .build();

        Retriever naive = new AdvancedRetriever(corpus, gateway, new DemoConfig(DeploymentProfile.OFFLINE));
        Retriever advanced = new AdvancedRetriever(corpus, gateway,
                new DemoConfig(DeploymentProfile.ON_PREM_HOSTED, Feature.ADVANCED_RETRIEVAL));

        DemoSupport.kv("query", '"' + QUERY + '"' + "  (k=2)");

        System.out.println();
        DemoSupport.bullet("ADVANCED_RETRIEVAL off -> pass-through (top-2 by vector score):");
        print(naive.retrieve(new RetrievalQuery(QUERY, 2, null)));

        System.out.println();
        DemoSupport.bullet("ADVANCED_RETRIEVAL on -> rewrite + over-fetch(8) + re-rank (top-2):");
        print(advanced.retrieve(new RetrievalQuery(QUERY, 2, null)));
    }

    private static void print(List<RetrievedChunk> chunks) {
        for (RetrievedChunk c : chunks) {
            DemoSupport.kv(String.format("  %.2f  %s", c.score(), c.citation().sourceId()), c.text());
        }
    }

    /** A stand-in vector corpus: returns its fixed chunks (in vector-score order), capped to k. */
    private static final class FixedCorpus implements Retriever {

        private static final List<RetrievedChunk> CHUNKS = List.of(
                chunk("Q3 marketing campaign retrospective and ad-spend analysis", 0.82, "docs/marketing-q3"),
                chunk("Customer support ticket volume trends by week", 0.71, "docs/support-trends"),
                chunk("orders table schema: columns order_id, customer_id, total, status", 0.55, "schema/orders"),
                chunk("orders dataset row counts and data-freshness checks", 0.50, "docs/orders-freshness"));

        @Override
        public List<RetrievedChunk> retrieve(RetrievalQuery query) {
            int n = Math.min(query.k(), CHUNKS.size());
            return List.copyOf(CHUNKS.subList(0, n));
        }

        private static RetrievedChunk chunk(String text, double score, String sourceId) {
            return new RetrievedChunk(text, score, new Citation(sourceId, sourceId, ""));
        }
    }
}
