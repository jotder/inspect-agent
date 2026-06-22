package com.eoiagent.knowledge;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.Citation;
import com.eoiagent.core.ConfigKey;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdvancedRetriever (T-208): pass-through when ADVANCED_RETRIEVAL is off; when on, rewrites the query,
 * routes to a corpus subset, over-fetches and re-ranks by lexical overlap with the original query.
 */
class AdvancedRetrieverTest {

    private static RetrievedChunk chunk(String text, double score, String sourceId) {
        return new RetrievedChunk(text, score, new Citation(sourceId, "", ""));
    }

    private static RetrievalQuery query(String text, int k) {
        return new RetrievalQuery(text, k, MetadataFilter.none());
    }

    @Test
    void disabledFeaturePassesThroughUnchanged() {
        List<RetrievedChunk> fixed = List.of(chunk("a", 0.9, "s1"));
        RecordingRetriever delegate = new RecordingRetriever(fixed);
        FixedRewriteGateway gateway = new FixedRewriteGateway("REWRITTEN");
        AdvancedRetriever retriever = new AdvancedRetriever(delegate, gateway, new FakeConfig(false));

        RetrievalQuery original = query("anything", 3);
        List<RetrievedChunk> result = retriever.retrieve(original);

        assertThat(result).isEqualTo(fixed);
        assertThat(delegate.last).isSameAs(original); // query untouched
        assertThat(gateway.chatCalls).isZero();        // no rewrite when disabled
    }

    @Test
    void enabledRewritesQueryAndOverfetches() {
        RecordingRetriever delegate = new RecordingRetriever(List.of());
        FixedRewriteGateway gateway = new FixedRewriteGateway("expanded search query");
        AdvancedRetriever retriever = new AdvancedRetriever(delegate, gateway, new FakeConfig(true));

        retriever.retrieve(query("help me please", 2)); // no schema/pipeline keywords → no routing

        assertThat(gateway.chatCalls).isEqualTo(1);
        assertThat(delegate.last.text()).isEqualTo("expanded search query"); // rewritten text used
        assertThat(delegate.last.k()).isEqualTo(8);                          // 2 × OVERFETCH(4)
        assertThat(delegate.last.filter().constraints()).doesNotContainKey("sourceType");
    }

    @Test
    void enabledRoutesSchemaQueryToSchemaConfigSubset() {
        RecordingRetriever delegate = new RecordingRetriever(List.of());
        AdvancedRetriever retriever = new AdvancedRetriever(
                delegate, new FixedRewriteGateway("rewritten"), new FakeConfig(true));

        retriever.retrieve(query("show the orders schema columns", 3));

        assertThat(delegate.last.filter().constraints()).containsEntry("sourceType", "SCHEMA_CONFIG");
    }

    @Test
    void enabledReRanksByLexicalOverlapAndReturnsTopK() {
        // The top vector hit has no lexical overlap; a lower vector hit matches both query terms.
        List<RetrievedChunk> candidates = List.of(
                chunk("apple pie", 0.90, "s1"),               // 0.90 + 0.0  = 0.90
                chunk("banana smoothie recipe", 0.50, "s2"),  // 0.50 + 1.0  = 1.50
                chunk("orange juice", 0.40, "s3"));           // 0.40 + 0.0  = 0.40
        RecordingRetriever delegate = new RecordingRetriever(candidates);
        AdvancedRetriever retriever = new AdvancedRetriever(
                delegate, new FixedRewriteGateway("banana smoothie"), new FakeConfig(true));

        List<RetrievedChunk> result = retriever.retrieve(query("banana smoothie", 2));

        assertThat(result).hasSize(2); // top-k
        assertThat(result.get(0).text()).isEqualTo("banana smoothie recipe"); // lexical boost wins
        assertThat(result.get(0).score()).isGreaterThan(result.get(1).score()); // ordered desc
        assertThat(result).extracting(RetrievedChunk::text).doesNotContain("orange juice"); // dropped
    }

    // ── test doubles ──────────────────────────────────────────────────────────────────────────────

    /** Records the query it last received and returns a preset candidate list. */
    static final class RecordingRetriever implements Retriever {
        RetrievalQuery last;
        private final List<RetrievedChunk> toReturn;

        RecordingRetriever(List<RetrievedChunk> toReturn) {
            this.toReturn = toReturn;
        }

        @Override
        public List<RetrievedChunk> retrieve(RetrievalQuery query) {
            this.last = query;
            return toReturn;
        }
    }

    /** Returns a fixed rewrite for any chat; counts calls. */
    static final class FixedRewriteGateway implements LlmGateway {
        private static final ModelInfo MODEL = new ModelInfo("scripted", "stub", true);
        private final String reply;
        int chatCalls = 0;

        FixedRewriteGateway(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            chatCalls++;
            return new ChatResult(reply, List.of(), MODEL, null);
        }

        @Override
        public void chatStream(ChatRequest request, TokenSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelInfo activeChatModel() {
            return MODEL;
        }

        @Override
        public boolean isAvailable(ModelRole role) {
            return true;
        }
    }

    /** Toggles the ADVANCED_RETRIEVAL feature; defaults for everything else. */
    static final class FakeConfig implements ConfigProvider {
        private final boolean advanced;

        FakeConfig(boolean advanced) {
            this.advanced = advanced;
        }

        @Override
        public DeploymentProfile profile() {
            return DeploymentProfile.OFFLINE;
        }

        @Override
        public <T> T get(ConfigKey<T> key) {
            return key.defaultValue();
        }

        @Override
        public boolean featureEnabled(Feature feature) {
            return feature == Feature.ADVANCED_RETRIEVAL && advanced;
        }
    }
}
