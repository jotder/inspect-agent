package com.eoiagent.app.reference;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.KnowledgeSource;
import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PackConfig;
import com.eoiagent.app.PackMetadata;
import com.eoiagent.app.PolicyProfile;
import com.eoiagent.app.PromptProfile;
import com.eoiagent.app.ToolProvider;
import com.eoiagent.core.AppId;

import java.util.List;

/**
 * The worked reference {@link ApplicationPack} for "Acme Lakehouse Suite" — the canonical answer to
 * "what does a filled-in pack look like?". Runs on the OFFLINE profile (no network, no API keys), and
 * is assembled by {@code PlatformBuilder.pack(new ReferenceApplicationPack()).start()}.
 *
 * <p>To onboard a real product: copy this module, rename the package/{@code AppId}, and replace the
 * sample providers and {@code src/main/resources/acme/} corpus with the product's own.
 */
public final class ReferenceApplicationPack implements ApplicationPack {

    @Override
    public PackMetadata metadata() {
        return new PackMetadata(new AppId("acme-lakehouse"), "Acme Lakehouse Suite", "0.1.0");
    }

    @Override
    public ModelProfile modelProfile() {
        return new ReferenceModelProfile();
    }

    @Override
    public List<KnowledgeSource> knowledgeSources() {
        return List.of(new ProductDocSource(), new SchemaConfigSource(), new PipelineConfigSource());
    }

    @Override
    public ToolProvider toolProvider() {
        return new ReferenceToolProvider();
    }

    @Override
    public NavigationCatalog navigationCatalog() {
        return new ReferenceNavigationCatalog();
    }

    @Override
    public PromptProfile promptProfile() {
        return new ReferencePromptProfile();
    }

    @Override
    public PolicyProfile policyProfile() {
        return new ReferencePolicyProfile();
    }

    @Override
    public PackConfig config() {
        return new ReferencePackConfig();
    }
}
