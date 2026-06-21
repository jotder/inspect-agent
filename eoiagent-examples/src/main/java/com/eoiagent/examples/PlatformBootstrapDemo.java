package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.Feature;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.platform.AgentPlatform;

import java.util.Map;

/**
 * Showcases Flow 0 — assembling a usable {@code AgentService} from an Application Pack with one call.
 * Boots the reference pack, prints its identity and deployment posture, and confirms the wired service.
 */
public final class PlatformBootstrapDemo {

    private PlatformBootstrapDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Platform bootstrap (Flow 0)");

        ReferenceApplicationPack pack = new ReferenceApplicationPack();
        StubLlmGateway offline = StubLlmGateway.builder().defaultReplyText("(offline stub reply)").build();

        try (AgentPlatform platform = DemoSupport.boot(DemoSupport.chooseGateway(offline), null)) {
            DemoSupport.kv("pack", platform.pack().name() + " v" + platform.pack().version());
            DemoSupport.kv("appId", platform.pack().appId().value());
            DemoSupport.kv("deployment profile", pack.config().profile());
            DemoSupport.kv("chat model", pack.modelProfile().chat().provider()
                    + "/" + pack.modelProfile().chat().modelId());
            DemoSupport.kv("embedding model", pack.modelProfile().embedding().provider());
            DemoSupport.kv("hosted fallback", pack.modelProfile().routing().allowHostedFallback());

            System.out.println("  feature overrides (restrict-only under OFFLINE):");
            for (Map.Entry<Feature, Boolean> e : pack.config().featureOverrides().entrySet()) {
                DemoSupport.bullet(e.getKey() + " = " + e.getValue());
            }

            DemoSupport.kv("agentService wired", platform.agentService() != null);
            System.out.println("  Platform assembled and ready - no network required.");
        }
    }
}
