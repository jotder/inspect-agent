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
import com.eoiagent.observability.AuditSink;
import com.eoiagent.observability.Slf4jAuditSink;
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

        LlmGateway gateway;
        if (gatewayOverride != null) {
            gateway = gatewayOverride;
        } else {
            gateway = buildGateway(pack.modelProfile(), config);
            track(owned, gateway);
        }

        PolicyEngine policy = new ProfilePolicyEngine(pack.policyProfile());
        DefaultToolRegistry registry = new DefaultToolRegistry(policy, audit);
        for (Tool tool : pack.toolProvider().tools()) {
            registry.register(tool);
            track(owned, tool); // a host tool may hold a resource (e.g. a JDBC handle)
        }

        Scratchpad scratchpad = new InMemoryScratchpad();
        track(owned, scratchpad);

        Guardrail guardrail = new Lc4jInputGuardrail();

        Orchestrator orchestrator = new ReActOrchestrator(gateway, registry, scratchpad, audit, config);
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
    private static LlmGateway buildGateway(ModelProfile modelProfile, ConfigProvider config) {
        ModelSelection chat = modelProfile.chat();
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
