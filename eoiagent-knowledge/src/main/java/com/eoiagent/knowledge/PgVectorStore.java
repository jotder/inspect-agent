package com.eoiagent.knowledge;

import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Production {@link WritableVectorStore} backed by PostgreSQL + pgvector via
 * {@code langchain4j-pgvector} (T-206, rag-knowledge spec). The pgvector dependency is experimental
 * ({@code 1.16.3-betaNN}) and quarantined to this adapter per ADR-0010 — no
 * {@code dev.langchain4j.store.embedding.pgvector} type appears outside this class.
 *
 * <p><strong>Feature-gated and fail-closed</strong> at construction (rag-knowledge AC7/AC8): it
 * throws {@link ConfigException} unless the {@code PGVECTOR} feature is enabled, and throws
 * {@link PolicyViolation} in the {@code OFFLINE} profile for any non-local JDBC URL (never opens a
 * remote connection offline). The {@code add}/{@code search}/{@code removeBySourceId} mapping mirrors
 * {@link InMemoryVectorStore} since both wrap an LC4j {@code EmbeddingStore<TextSegment>}.
 */
public final class PgVectorStore implements WritableVectorStore {

    /** Connection + table settings for the pgvector-backed store. */
    public record Settings(String jdbcUrl, String user, String password, String table, int dimension) {
    }

    private static final Set<String> LOCAL_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");

    private final PgVectorEmbeddingStore store;

    public PgVectorStore(Settings settings, DeploymentProfile profile, boolean pgvectorEnabled) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(profile, "profile");
        if (!pgvectorEnabled) {
            throw new ConfigException(
                    "pgvector vector store requires the PGVECTOR feature, which is not enabled"); // AC8
        }
        Endpoint ep = parse(settings.jdbcUrl());
        if (profile == DeploymentProfile.OFFLINE && !LOCAL_HOSTS.contains(ep.host().toLowerCase(Locale.ROOT))) {
            throw new PolicyViolation(
                    "OFFLINE profile forbids a non-local pgvector URL: " + settings.jdbcUrl()); // AC7
        }
        this.store = PgVectorEmbeddingStore.builder()
                .host(ep.host())
                .port(ep.port())
                .user(settings.user())
                .password(settings.password())
                .database(ep.database())
                .table(settings.table())
                .dimension(settings.dimension())
                .createTable(true)
                .build();
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(chunks.size());
        List<Embedding> embeddings = new ArrayList<>(chunks.size());
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (EmbeddedChunk c : chunks) {
            ids.add(c.id());
            embeddings.add(Embedding.from(c.vector()));
            segments.add(TextSegment.from(c.text(), new Metadata(c.metadata())));
        }
        store.addAll(ids, embeddings, segments);
    }

    @Override
    public List<Match> search(float[] queryVector, int k, MetadataFilter filter) {
        var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(k);
        Filter f = toFilter(filter);
        if (f != null) {
            builder.filter(f);
        }
        EmbeddingSearchResult<TextSegment> result = store.search(builder.build());

        List<Match> matches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : result.matches()) {
            TextSegment segment = m.embedded();
            EmbeddedChunk chunk = new EmbeddedChunk(
                    m.embeddingId(), segment.text(), m.embedding().vector(),
                    toStringMap(segment.metadata().toMap()));
            matches.add(new Match(chunk, m.score()));
        }
        return matches;
    }

    @Override
    public void removeBySourceId(String sourceId) {
        store.removeAll(MetadataFilterBuilder.metadataKey("sourceId").isEqualTo(sourceId));
    }

    private record Endpoint(String host, int port, String database) {
    }

    /** Parses {@code jdbc:postgresql://host[:port]/db[?params]} into host/port/database. */
    private static Endpoint parse(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new ConfigException("invalid pgvector JDBC URL: " + jdbcUrl);
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length())); // postgresql://host:port/db?params
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ConfigException("pgvector JDBC URL missing host: " + jdbcUrl);
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getPath();
        String database = (path == null || path.length() <= 1) ? "" : path.substring(1);
        return new Endpoint(host, port, database);
    }

    private static Filter toFilter(MetadataFilter filter) {
        if (filter == null || filter.constraints().isEmpty()) {
            return null;
        }
        Filter combined = null;
        for (Map.Entry<String, String> e : filter.constraints().entrySet()) {
            Filter clause = MetadataFilterBuilder.metadataKey(e.getKey()).isEqualTo(e.getValue());
            combined = (combined == null) ? clause : combined.and(clause);
        }
        return combined;
    }

    private static Map<String, String> toStringMap(Map<String, Object> map) {
        Map<String, String> out = new HashMap<>();
        map.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }
}
