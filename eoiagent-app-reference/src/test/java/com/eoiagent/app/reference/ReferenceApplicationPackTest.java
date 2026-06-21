package com.eoiagent.app.reference;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.ParamSpec;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.Role;
import com.eoiagent.knowledge.DocumentSource;
import com.eoiagent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T-116 unit checks of the reference pack's providers (AC4/AC5/AC6/AC9 + model + corpus + nav). */
class ReferenceApplicationPackTest {

    private final ApplicationPack pack = new ReferenceApplicationPack();

    @Test
    void metadataIdentifiesAcmeLakehouse() {
        assertThat(pack.metadata().appId()).isEqualTo(new AppId("acme-lakehouse"));
        assertThat(pack.metadata().name()).isNotBlank();
        assertThat(pack.metadata().version()).isNotBlank();
    }

    @Test
    void modelProfileIsOfflineWithNoHostedFallback() { // AC7 (model side)
        assertThat(pack.modelProfile().chat().local()).isTrue();
        assertThat(pack.modelProfile().embedding().provider()).isEqualTo("onnx-all-minilm");
        assertThat(pack.modelProfile().routing().allowHostedFallback()).isFalse();
    }

    @Test
    void allProvidersAreNonNull() {
        assertThat(pack.metadata()).isNotNull();
        assertThat(pack.modelProfile()).isNotNull();
        assertThat(pack.knowledgeSources()).isNotNull();
        assertThat(pack.toolProvider()).isNotNull();
        assertThat(pack.navigationCatalog()).isNotNull();
        assertThat(pack.promptProfile()).isNotNull();
        assertThat(pack.policyProfile()).isNotNull();
        assertThat(pack.config()).isNotNull();
    }

    @Test
    void toolsAreAllReadOnlyWithRoleAndCapabilityAndNoMcp() { // AC4
        List<Tool> tools = pack.toolProvider().tools();
        assertThat(tools).hasSize(3);
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.spec().mutating()).as("tool %s must be read-only", t.spec().name()).isFalse();
            assertThat(t.spec().requiredRole()).isNotNull();
            assertThat(t.spec().capability()).isNotNull();
        });
        assertThat(pack.toolProvider().mcpServers()).isEmpty();
    }

    @Test
    void navigationCatalogHasUniquePagesAndRequiredParams() { // AC5
        List<PageDescriptor> pages = pack.navigationCatalog().pages();
        Set<String> ids = new HashSet<>();
        for (PageDescriptor page : pages) {
            assertThat(ids.add(page.pageId())).as("duplicate pageId %s", page.pageId()).isTrue();
        }

        PageDescriptor pipeline = pack.navigationCatalog().find("pipeline-detail").orElseThrow();
        ParamSpec pipelineId = pipeline.params().stream()
                .filter(p -> p.name().equals("pipelineId")).findFirst().orElseThrow();
        assertThat(pipelineId.required()).isTrue();
    }

    @Test
    void aNavigationIntentCanTargetACatalogPageWithItsRequiredParam() {
        // Pack-level proof of the navigation contract (end-to-end emission from the orchestrator is
        // a Phase-2 runtime feature). A "show me revenue" answer would carry this intent.
        PageDescriptor kpi = pack.navigationCatalog().find("kpi-dashboard").orElseThrow();
        String requiredParam = kpi.params().stream()
                .filter(ParamSpec::required).map(ParamSpec::name).findFirst().orElseThrow();
        assertThat(requiredParam).isEqualTo("metric");

        NavigationIntent intent = new NavigationIntent("kpi-dashboard",
                java.util.Map.of("metric", "revenue"), "User asked for revenue KPIs");
        assertThat(pack.navigationCatalog().find(intent.targetPageId())).isPresent();
        assertThat(intent.parameters()).containsKey(requiredParam);
    }

    @Test
    void policyMapsRolesAndGrantsOnlyReadCapabilitiesToUser() { // AC6
        assertThat(pack.policyProfile().mapRole("engineer")).isEqualTo(Role.ANALYST);
        assertThat(pack.policyProfile().mapRole("admin")).isEqualTo(Role.ADMIN);
        assertThat(pack.policyProfile().mapRole("nope")).isEqualTo(Role.USER);
        assertThat(pack.policyProfile().mapRole(null)).isEqualTo(Role.USER);

        Set<Capability> userGrants = pack.policyProfile().grants(Role.USER);
        assertThat(userGrants).containsExactlyInAnyOrder(Capability.READ_DOCS, Capability.READ_METADATA);
        assertThat(userGrants).doesNotContain(Capability.WRITE_DATASTORE, Capability.RUN_PIPELINE,
                Capability.EDIT_CONFIG, Capability.TRIGGER_JOB, Capability.AUTHOR_PIPELINE);
    }

    @Test
    void systemPromptIsNonNullForEveryGoalKind() { // AC9
        for (GoalKind kind : GoalKind.values()) {
            assertThat(pack.promptProfile().systemPrompt(kind))
                    .as("systemPrompt(%s)", kind).isNotNull().isNotBlank();
        }
        assertThat(pack.promptProfile().persona()).isNotBlank();
    }

    @Test
    void knowledgeSourcesResolveToBundledClasspathResources() {
        List<KnowledgeSource> sources = pack.knowledgeSources();
        assertThat(sources).hasSize(3);
        for (KnowledgeSource source : sources) {
            assertThat(source.id()).isNotBlank();
            assertThat(source.kind()).isNotNull();
            assertThat(source.options()).isNotNull();
            assertThat(source.resolve()).isNotEmpty();
            for (DocumentSource doc : source.resolve()) {
                assertThat(getClass().getResource(doc.uri()))
                        .as("bundled resource %s for source %s", doc.uri(), source.id()).isNotNull();
            }
        }
    }

    @Test
    void packConfigProfileIsOffline() {
        assertThat(pack.config().profile()).isEqualTo(DeploymentProfile.OFFLINE);
    }
}
