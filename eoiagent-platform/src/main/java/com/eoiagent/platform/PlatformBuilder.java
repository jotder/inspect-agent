package com.eoiagent.platform;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.ModelSelection;
import com.eoiagent.app.PackConfig;
import com.eoiagent.config.ConfigKeys;
import com.eoiagent.config.ConfigProvider;
import com.eoiagent.config.ProgrammaticConfigProvider;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Feature;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.DefaultAgentService;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.OllamaChatAdapter;
import com.eoiagent.model.OpenAiCompatibleChatAdapter;
import com.eoiagent.model.RoutingLlmGateway;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.IngestReport;
import com.eoiagent.core.IngestRequest;
import com.eoiagent.knowledge.DefaultDocumentIngestor;
import com.eoiagent.knowledge.DefaultRetriever;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.knowledge.InMemoryVectorStore;
import com.eoiagent.knowledge.OnnxEmbeddingAdapter;
import com.eoiagent.knowledge.Retriever;
import com.eoiagent.memory.InMemoryMemoryStore;
import com.eoiagent.memory.MemoryStore;
import com.eoiagent.model.OllamaEmbeddingAdapter;
import com.eoiagent.core.Feature;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.observability.NoopTraceCollector;
import com.eoiagent.observability.Slf4jAuditSink;
import com.eoiagent.observability.TraceCollector;
import com.eoiagent.safety.ApprovalHandler;
import com.eoiagent.safety.CallbackApprovalGate;
import com.eoiagent.runtime.Orchestrator;
import com.eoiagent.runtime.ReActOrchestrator;
import com.eoiagent.safety.Guardrail;
import com.eoiagent.safety.Lc4jInputGuardrail;
import com.eoiagent.safety.PolicyEngine;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.scratchpad.Scratchpad;
import com.eoiagent.tool.DefaultToolRegistry;
import com.eoiagent.tool.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The single bootstrap entry point (Flow 0): {@link #pack(ApplicationPack)} then {@link #start()}
 * validates the pack, builds a {@link ConfigProvider} from its {@link PackConfig}, wires the core
 * adapters (gateway, policy engine, tool registry, scratchpad, guardrail, orchestrator) and returns a
 * ready {@link AgentPlatform} whose {@link AgentService} is bound to the pack's {@code AppId}.
 *
 * <p>Phase-1 scope: the assembled service drives the read-only ReAct loop offline. Knowledge
 * ingestion, memory and true streaming are not yet consumable by that loop (Phase 2), so the pack's
 * {@code KnowledgeSource}/{@code PromptProfile}/{@code NavigationCatalog} are validated here but not
 * yet threaded into the orchestrator.
 *
 * <p>{@link #configProvider(ConfigProvider)}, {@link #auditSink(AuditSink)} and
 * {@link #llmGateway(LlmGateway)} are optional host/embedding overrides; an injected adapter is the
 * host's to close. {@code llmGateway} is the seam through which a {@code StubLlmGateway} drives the
 * platform offline in tests (the model-from-profile factory below only knows real local providers).
 */
public final class PlatformBuilder {

    private ApplicationPack pack;
    private ConfigProvider configOverride;
    private AuditSink auditOverride;
    private LlmGateway gatewayOverride;
    private TraceCollector traceOverride;
    private MemoryStore memoryOverride;
    private Retriever retrieverOverride;
    private ApprovalHandler approvalHandlerOverride;

    /** The application pack to assemble. Required. */
    public PlatformBuilder pack(ApplicationPack pack) {
        this.pack = Objects.requireNonNull(pack, "pack");
        return this;
    }

    /** Optional host override; when set, replaces the provider built from {@code PackConfig}. */
    public PlatformBuilder configProvider(ConfigProvider configProvider) {
        this.configOverride = configProvider;
        return this;
    }

    /** Optional host override; when set, replaces the default {@link Slf4jAuditSink}. */
    public PlatformBuilder auditSink(AuditSink auditSink) {
        this.auditOverride = auditSink;
        return this;
    }

    /** Optional override; when set, replaces the gateway built from the pack's {@link ModelProfile}. */
    public PlatformBuilder llmGateway(LlmGateway gateway) {
        this.gatewayOverride = gateway;
        return this;
    }

    /**
     * Optional host override; when set, replaces the default {@link NoopTraceCollector} (T-401).
     * Tracing is best-effort spans alongside the mandatory audit trail — never wire an OTel/OTLP
     * collector under {@code OFFLINE} (spec AC6, zero-egress).
     */
    public PlatformBuilder traceCollector(TraceCollector traceCollector) {
        this.traceOverride = traceCollector;
        return this;
    }

    /**
     * Optional host override for session memory (T-351); when set, replaces the default
     * {@link InMemoryMemoryStore} (e.g. with a {@code PostgresMemoryStore} so conversations
     * survive restarts). Session transcripts seed each run's model context — memory is explicit
     * platform behavior, not something the model "just has".
     */
    public PlatformBuilder memoryStore(MemoryStore memoryStore) {
        this.memoryOverride = memoryStore;
        return this;
    }

    /**
     * Optional host override for retrieval (T-352); when set, the platform skips building the
     * default knowledge stack (embedding model + in-memory vector store + ingestion of the pack's
     * {@code KnowledgeSource}s) and uses this retriever for the RAG step instead.
     */
    public PlatformBuilder retriever(Retriever retriever) {
        this.retrieverOverride = retriever;
        return this;
    }

    /**
     * The host's human-in-the-loop callback for mutating actions (T-354). Only consulted when the
     * {@code MUTATING_ACTIONS} feature is enabled; without a handler the approval gate is headless
     * and every request is DENIED (fail-closed) — mutating tools can never run un-approved.
     */
    public PlatformBuilder approvalHandler(ApprovalHandler approvalHandler) {
        this.approvalHandlerOverride = approvalHandler;
        return this;
    }

    /** Validate → wire → ready. Throws before building anything if the pack is invalid. */
    public AgentPlatform start() {
        if (pack == null) {
            throw new ConfigException("PlatformBuilder.pack(...) is required before start()");
        }
        PackValidator.validate(pack);

        // Adapters the platform builds are owned by it and closed on AgentPlatform.close(); adapters the
        // host injected via an override are the host's to close and are not tracked here.
        List<AutoCloseable> owned = new ArrayList<>();

        ConfigProvider config = configOverride != null ? configOverride : buildConfig(pack.config());
        AuditSink audit = auditOverride != null ? auditOverride : new Slf4jAuditSink();
        TraceCollector trace = traceOverride != null ? traceOverride : new NoopTraceCollector();

        LlmGateway gateway;
        if (gatewayOverride != null) {
            gateway = gatewayOverride;
        } else {
            gateway = buildGateway(pack.modelProfile(), config);
            track(owned, gateway);
        }

        // T-354: the pack's PolicyProfile is a restriction overlay on the default grant-table
        // ceiling — a pack narrows permissions, never widens them.
        PolicyEngine policy = new CeilingPolicyEngine(new ProfilePolicyEngine(pack.policyProfile()));

        // T-354: with MUTATING_ACTIONS enabled, the registry runs the full mutating dispatch
        // (policy → dry-run → approval → audit; C4 invariant). No ApprovalHandler wired means the
        // gate is headless and every approval is DENIED — fail-closed, never fail-open.
        DefaultToolRegistry registry;
        if (config.featureEnabled(Feature.MUTATING_ACTIONS)) {
            CallbackApprovalGate.Builder gate = CallbackApprovalGate.builder();
            if (approvalHandlerOverride != null) {
                gate.handler(approvalHandlerOverride);
            }
            registry = new DefaultToolRegistry(policy, gate.build(), config, audit);
        } else {
            registry = new DefaultToolRegistry(policy, audit);
        }
        for (Tool tool : pack.toolProvider().tools()) {
            registry.register(tool);
            track(owned, tool); // a host tool may hold a resource (e.g. a JDBC handle)
        }
        // T-353: packs with navigable pages get the reserved navigation tool; the model proposes a
        // page, the tool validates it against the catalog, and the loop returns a typed intent.
        if (!pack.navigationCatalog().pages().isEmpty()) {
            registry.register(new NavigationTool(pack.navigationCatalog()));
        }

        Scratchpad scratchpad = new InMemoryScratchpad();
        track(owned, scratchpad);

        Guardrail guardrail = new Lc4jInputGuardrail();

        MemoryStore memory = memoryOverride != null ? memoryOverride : new InMemoryMemoryStore();

        // T-352: RAG in the loop. The pack's corpus is ingested at bootstrap (Flow 0) so the very
        // first question already retrieves; a pack without knowledge sources gets no retriever.
        Retriever retriever = retrieverOverride;
        if (retriever == null && !pack.knowledgeSources().isEmpty()) {
            retriever = buildRetrieval(pack, config);
        }

        Orchestrator orchestrator = ReActOrchestrator.builder()
                .gateway(gateway)
                .tools(registry)
                .scratchpad(scratchpad)
                .audit(audit)
                .config(config)
                .traceCollector(trace)
                .memoryStore(memory)
                .retriever(retriever)
                .systemPrompts(kind -> pack.promptProfile().systemPrompt(kind))
                .build();
        AgentService service = new DefaultAgentService(
                pack.metadata().appId(), config, orchestrator, guardrail, audit);

        return new DefaultAgentPlatform(service, pack.metadata(), owned);
    }

    /**
     * Builds a {@link ConfigProvider} from the pack: the shipped {@code eoiagent.*} defaults, then the
     * typed profile and feature overrides (which win over any string default for those keys).
     */
    private static ConfigProvider buildConfig(PackConfig packConfig) {
        Map<String, String> values = new HashMap<>();
        Map<String, String> defaults = packConfig.configDefaults();
        if (defaults != null) {
            values.putAll(defaults);
        }
        values.put(ConfigKeys.PROFILE.name(), packConfig.profile().name());
        Map<Feature, Boolean> overrides = packConfig.featureOverrides();
        if (overrides != null) {
            for (Map.Entry<Feature, Boolean> e : overrides.entrySet()) {
                values.put(featureKeyName(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return new ProgrammaticConfigProvider(values);
    }

    /**
     * Builds the chat gateway from the pack's {@link ModelProfile}, wrapped in a
     * {@link RoutingLlmGateway} so it fails closed offline. Only real local providers are recognised;
     * a {@code StubLlmGateway} reaches the platform through {@link #llmGateway(LlmGateway)} instead.
     */
    /**
     * Builds the default knowledge stack: the pack's embedding model, an in-memory vector store,
     * and one ingestion pass over every {@code KnowledgeSource} (idempotent per source id). Source
     * documents are enriched with the {@code sourceId}/{@code sourceType} metadata the ingestor
     * requires — the source id doubles as the citation id the eval harness asserts against.
     */
    private static Retriever buildRetrieval(ApplicationPack pack, ConfigProvider config) {
        dev.langchain4j.model.embedding.EmbeddingModel embedding =
                buildEmbeddingModel(withConfigOverrides(pack.modelProfile().embedding(), config,
                        com.eoiagent.model.ModelConfigKeys.EMBEDDING_PROVIDER,
                        com.eoiagent.model.ModelConfigKeys.EMBEDDING_MODEL_ID,
                        com.eoiagent.model.ModelConfigKeys.EMBEDDING_BASE_URL));
        InMemoryVectorStore store = new InMemoryVectorStore();
        DefaultDocumentIngestor ingestor = new DefaultDocumentIngestor(embedding, store);

        for (com.eoiagent.app.KnowledgeSource source : pack.knowledgeSources()) {
            String sourceType = sourceTypeFor(source);
            List<DocumentSource> enriched = new ArrayList<>();
            for (DocumentSource doc : source.resolve()) {
                Map<String, String> meta = new HashMap<>(doc.metadata() == null ? Map.of() : doc.metadata());
                meta.putIfAbsent("sourceId", source.id());
                meta.putIfAbsent("sourceType", sourceType);
                enriched.add(new DocumentSource(doc.uri(), doc.mediaType(), meta));
            }
            IngestReport report = ingestor.ingest(new IngestRequest(enriched, source.options()));
            if (report.documents() == 0) {
                throw new ConfigException("knowledge source '" + source.id() + "' ingested no documents: "
                        + report.warnings());
            }
        }
        return new DefaultRetriever(embedding, store);
    }

    private static String sourceTypeFor(com.eoiagent.app.KnowledgeSource source) {
        return switch (source.kind()) {
            case PRODUCT_DOC -> "PRODUCT_DOC";
            case CONFIG_FILE -> "PIPELINE_CONFIG";
            case SCHEMA_CONFIG -> "SCHEMA_CONFIG";
            case CUSTOM -> throw new ConfigException("CUSTOM knowledge source '" + source.id()
                    + "' needs a host-provided retriever (PlatformBuilder.retriever(...))");
        };
    }

    private static dev.langchain4j.model.embedding.EmbeddingModel buildEmbeddingModel(ModelSelection embedding) {
        String provider = embedding == null || embedding.provider() == null
                ? "" : embedding.provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "onnx", "onnx-all-minilm" -> new OnnxEmbeddingAdapter();
            case "ollama" -> new OllamaEmbeddingAdapter(embedding.baseUrl(), embedding.modelId());
            default -> throw new ConfigException("unsupported embedding provider '"
                    + (embedding == null ? null : embedding.provider())
                    + "' — supported: onnx-all-minilm, ollama");
        };
    }

    /**
     * ADR-0013 §1 — config outranks pack: {@code eoiagent.model.chat.*} keys, when set, override
     * the pack's {@link ModelProfile}, so a deployment swaps models without recompiling anything.
     */
    private static LlmGateway buildGateway(ModelProfile modelProfile, ConfigProvider config) {
        ModelSelection chat = withConfigOverrides(modelProfile.chat(), config,
                com.eoiagent.model.ModelConfigKeys.CHAT_PROVIDER,
                com.eoiagent.model.ModelConfigKeys.CHAT_MODEL_ID,
                com.eoiagent.model.ModelConfigKeys.CHAT_BASE_URL);
        String provider = chat.provider() == null ? "" : chat.provider().trim().toLowerCase(Locale.ROOT);
        LlmGateway backend = switch (provider) {
            case "ollama" -> new OllamaChatAdapter(chat.baseUrl(), chat.modelId());
            case "openai", "openai-compatible" ->
                    new OpenAiCompatibleChatAdapter(chat.baseUrl(), chat.modelId(), null);
            default -> throw new ConfigException("unsupported chat provider '" + chat.provider()
                    + "' — supported: ollama, openai-compatible "
                    + "(inject a gateway via PlatformBuilder.llmGateway(...) for tests/stubs)");
        };
        return RoutingLlmGateway.builder().config(config).addChatBackend(backend).build();
    }

    /** Overlays non-blank config values onto the pack's selection (provider/modelId/baseUrl). */
    private static ModelSelection withConfigOverrides(ModelSelection pack, ConfigProvider config,
                                                      com.eoiagent.core.ConfigKey<String> providerKey,
                                                      com.eoiagent.core.ConfigKey<String> modelIdKey,
                                                      com.eoiagent.core.ConfigKey<String> baseUrlKey) {
        String provider = firstNonBlank(config.get(providerKey), pack == null ? null : pack.provider());
        String modelId = firstNonBlank(config.get(modelIdKey), pack == null ? null : pack.modelId());
        String baseUrl = firstNonBlank(config.get(baseUrlKey), pack == null ? null : pack.baseUrl());
        return new ModelSelection(provider, modelId, baseUrl, pack == null || pack.local());
    }

    private static String firstNonBlank(String override, String packValue) {
        return override != null && !override.isBlank() ? override : packValue;
    }

    private static void track(List<AutoCloseable> owned, Object adapter) {
        if (adapter instanceof AutoCloseable c) {
            owned.add(c);
        }
    }

    private static String featureKeyName(Feature feature) {
        return switch (feature) {
            case HOSTED_MODELS -> ConfigKeys.HOSTED_MODELS_ENABLED.name();
            case MUTATING_ACTIONS -> ConfigKeys.MUTATING_ACTIONS_ENABLED.name();
            case MCP_TOOLS -> ConfigKeys.MCP_TOOLS_ENABLED.name();
            case PGVECTOR -> ConfigKeys.PGVECTOR_ENABLED.name();
            case ADVANCED_RETRIEVAL -> ConfigKeys.ADVANCED_RETRIEVAL_ENABLED.name();
            case LANGGRAPH_CHECKPOINTING -> ConfigKeys.LANGGRAPH_CHECKPOINTING_ENABLED.name();
            case LONG_TERM_MEMORY -> ConfigKeys.LONG_TERM_MEMORY_ENABLED.name();
        };
    }
}
