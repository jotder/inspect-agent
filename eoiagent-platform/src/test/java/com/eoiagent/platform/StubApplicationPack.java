package com.eoiagent.platform;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.McpServerRef;
import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.ModelSelection;
import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PackConfig;
import com.eoiagent.app.PackMetadata;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.PolicyProfile;
import com.eoiagent.app.PromptProfile;
import com.eoiagent.app.RoutingPolicy;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.tool.Tool;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A complete, valid OFFLINE {@link ApplicationPack} for tests, with fluent overrides so a single test
 * can null out one provider (validation), flip routing to hosted fallback, or attach a tool. Uses a
 * {@code StubLlmGateway} injected via {@link PlatformBuilder#llmGateway} — its chat provider is "stub"
 * and is therefore never read by the model-from-profile factory.
 */
final class StubApplicationPack implements ApplicationPack {

    private PackMetadata metadata = new PackMetadata(new AppId("stub-app"), "Stub Pack", "1.0.0");
    private ModelProfile modelProfile = offlineModelProfile(false);
    private List<KnowledgeSource> knowledgeSources = List.of();
    private ToolProvider toolProvider = toolProvider(List.of());
    private NavigationCatalog navigationCatalog = navigationCatalog(List.of());
    private PromptProfile promptProfile = DEFAULT_PROMPTS;
    private PolicyProfile policyProfile = READ_ONLY_POLICY;
    private PackConfig config = packConfig(DeploymentProfile.OFFLINE, Map.of(), Map.of());

    @Override public PackMetadata metadata() { return metadata; }
    @Override public ModelProfile modelProfile() { return modelProfile; }
    @Override public List<KnowledgeSource> knowledgeSources() { return knowledgeSources; }
    @Override public ToolProvider toolProvider() { return toolProvider; }
    @Override public NavigationCatalog navigationCatalog() { return navigationCatalog; }
    @Override public PromptProfile promptProfile() { return promptProfile; }
    @Override public PolicyProfile policyProfile() { return policyProfile; }
    @Override public PackConfig config() { return config; }

    // --- fluent overrides -------------------------------------------------------------------------

    StubApplicationPack withMetadata(PackMetadata m) { this.metadata = m; return this; }
    StubApplicationPack withModelProfile(ModelProfile m) { this.modelProfile = m; return this; }
    StubApplicationPack withKnowledgeSources(List<KnowledgeSource> k) { this.knowledgeSources = k; return this; }
    StubApplicationPack withToolProvider(ToolProvider t) { this.toolProvider = t; return this; }
    StubApplicationPack withNavigationCatalog(NavigationCatalog n) { this.navigationCatalog = n; return this; }
    StubApplicationPack withPromptProfile(PromptProfile p) { this.promptProfile = p; return this; }
    StubApplicationPack withPolicyProfile(PolicyProfile p) { this.policyProfile = p; return this; }
    StubApplicationPack withConfig(PackConfig c) { this.config = c; return this; }

    StubApplicationPack withTools(List<Tool> tools) { return withToolProvider(toolProvider(tools)); }
    StubApplicationPack withPages(List<PageDescriptor> pages) { return withNavigationCatalog(navigationCatalog(pages)); }

    // --- provider factories -----------------------------------------------------------------------

    static ModelProfile offlineModelProfile(boolean allowHostedFallback) {
        return new StubModelProfile(
                new ModelSelection("stub", "stub-model", null, true),
                new ModelSelection("onnx-all-minilm", "all-minilm-l6-v2", null, true),
                new RoutingPolicy(List.of("stub"), allowHostedFallback));
    }

    static ToolProvider toolProvider(List<Tool> tools) {
        return new StubToolProvider(tools, List.of());
    }

    static NavigationCatalog navigationCatalog(List<PageDescriptor> pages) {
        return new StubNavigationCatalog(pages);
    }

    static PackConfig packConfig(DeploymentProfile profile,
                                 Map<Feature, Boolean> overrides,
                                 Map<String, String> defaults) {
        return new StubPackConfig(profile, overrides, defaults);
    }

    private static final PromptProfile DEFAULT_PROMPTS = new PromptProfile() {
        @Override public String systemPrompt(GoalKind kind) { return "You are a helpful assistant."; }
        @Override public String persona() { return "EOI assistant"; }
        @Override public Map<String, String> domainGlossary() { return Map.of(); }
    };

    /** Grants the read-only capabilities to every role; maps known role names, else USER. */
    private static final PolicyProfile READ_ONLY_POLICY = new PolicyProfile() {
        private final Set<Capability> readOnly = EnumSet.of(
                Capability.READ_DOCS, Capability.READ_METADATA, Capability.READ_SCHEMA,
                Capability.RUN_SQL_READONLY, Capability.GENERATE_SQL, Capability.INVESTIGATE);

        @Override
        public Role mapRole(String hostRole) {
            if (hostRole == null) {
                return Role.USER;
            }
            try {
                return Role.valueOf(hostRole.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Role.USER;
            }
        }

        @Override
        public Set<Capability> grants(Role role) {
            return readOnly;
        }
    };

    private record StubModelProfile(ModelSelection chat, ModelSelection embedding, RoutingPolicy routing)
            implements ModelProfile {
    }

    private record StubToolProvider(List<Tool> tools, List<McpServerRef> mcpServers) implements ToolProvider {
    }

    private record StubPackConfig(DeploymentProfile profile,
                                  Map<Feature, Boolean> featureOverrides,
                                  Map<String, String> configDefaults) implements PackConfig {
    }

    private record StubNavigationCatalog(List<PageDescriptor> pages) implements NavigationCatalog {
        @Override
        public Optional<PageDescriptor> find(String pageId) {
            return pages.stream().filter(p -> p.pageId().equals(pageId)).findFirst();
        }
    }
}
