package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.util.Map;

/**
 * T-354 / ADR-0013: swap models with CONFIG, not code. Corrects the misconception that
 * <strong>changing the model is an engineering project</strong>: the pack's {@code ModelProfile}
 * is only the <em>default</em> — the {@code eoiagent.model.chat.*} keys override it at assembly,
 * so a deployment moves to a newer model (qwen → Ornith → whatever ships next month) by editing
 * configuration and re-running the certification eval (T-356). Both boots below go through the
 * REAL assembly path; the first proves precedence (a bogus config provider beats the pack's valid
 * one and fails), the second swaps the model id cleanly.
 */
public final class ConfigSwapModelDemo {

    private ConfigSwapModelDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Pluggable models: config outranks the pack (ADR-0013)");

        System.out.println("  MISCONCEPTION: \"adopting a newer model means changing the code\"");
        System.out.println("  REALITY:       model choice is deployment config; adoption is a");
        System.out.println("                 certification-eval run, not an engineering project");
        System.out.println();

        ReferenceApplicationPack pack = new ReferenceApplicationPack();
        DemoSupport.kv("pack default (chat)", pack.modelProfile().chat().provider()
                + "/" + pack.modelProfile().chat().modelId());

        // 1) Precedence proof: a config override WINS over the pack's perfectly valid profile —
        //    here a bogus provider makes assembly fail, which could only happen if config won.
        System.out.println();
        System.out.println("  Boot 1: config override provider='frontier-lab-x' (not supported)");
        try (AgentPlatform ignored = new PlatformBuilder()
                .pack(pack)
                .configProvider(new DemoConfig(DeploymentProfile.OFFLINE,
                        Map.of("eoiagent.model.chat.provider", "frontier-lab-x")))
                .start()) {
            System.out.println("  UNEXPECTED: started");
        } catch (ConfigException e) {
            DemoSupport.bullet("assembly rejected it (config outranked the pack): " + e.getMessage());
        }

        // 2) The real swap: same pack, new model id — no recompilation, no new jar.
        System.out.println();
        System.out.println("  Boot 2: config override modelId='ornith-1.0-9b' (provider stays ollama)");
        try (AgentPlatform platform = new PlatformBuilder()
                .pack(pack)
                .configProvider(new DemoConfig(DeploymentProfile.OFFLINE,
                        Map.of("eoiagent.model.chat.modelId", "ornith-1.0-9b")))
                .start()) {
            DemoSupport.bullet("platform assembled against the swapped model - zero code changed");
            DemoSupport.bullet("agentService ready: " + (platform.agentService() != null));
        }

        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("the pack ModelProfile is a DEFAULT; eoiagent.model.chat.* overrides it");
        DemoSupport.bullet("swap = edit config + run the golden certification suite (T-356)");
        DemoSupport.bullet("per-task routing (coding model for SQL, general model for QA) is also config");
    }
}
