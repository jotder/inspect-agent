package com.eoiagent.platform;

import com.eoiagent.app.ApplicationPack;
import com.eoiagent.app.ModelProfile;
import com.eoiagent.app.PackConfig;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an {@link ApplicationPack} at {@code start()} — the platform never assembles a
 * misconfigured pack. Two failure modes (application-pack spec §Error handling):
 *
 * <ul>
 *   <li>A null pack, a null required provider, or a duplicate {@code pageId} → {@link ConfigException}
 *       naming the offending field (fail fast, never an NPE deeper in wiring).</li>
 *   <li>A pack whose profile contradicts the capability matrix — {@code OFFLINE} with hosted fallback,
 *       or a {@code featureOverride} that enables a feature the profile forbids → {@link PolicyViolation}.</li>
 * </ul>
 */
final class PackValidator {

    private PackValidator() {
    }

    static void validate(ApplicationPack pack) {
        if (pack == null) {
            throw new ConfigException("ApplicationPack must not be null");
        }
        // 1) Required providers present (ConfigException names the missing one rather than NPE later).
        require(pack.metadata(), "metadata");
        require(pack.metadata().appId(), "metadata.appId");
        ModelProfile model = require(pack.modelProfile(), "modelProfile");
        require(model.chat(), "modelProfile.chat");
        require(model.embedding(), "modelProfile.embedding");
        require(model.routing(), "modelProfile.routing");
        require(pack.knowledgeSources(), "knowledgeSources");
        require(pack.toolProvider(), "toolProvider");
        require(pack.toolProvider().tools(), "toolProvider.tools");
        require(pack.navigationCatalog(), "navigationCatalog");
        require(pack.navigationCatalog().pages(), "navigationCatalog.pages");
        require(pack.promptProfile(), "promptProfile");
        require(pack.policyProfile(), "policyProfile");
        PackConfig config = require(pack.config(), "config");
        DeploymentProfile profile = require(config.profile(), "config.profile");

        // 2) No null elements / no duplicate page ids.
        requireNoNulls(pack.knowledgeSources(), "knowledgeSources");
        requireNoNulls(pack.toolProvider().tools(), "toolProvider.tools");
        requireUniquePageIds(pack.navigationCatalog().pages());

        // 3) Profile/matrix consistency → PolicyViolation (never start an egressing OFFLINE pack).
        if (profile == DeploymentProfile.OFFLINE && model.routing().allowHostedFallback()) {
            throw new PolicyViolation("profile OFFLINE forbids hosted fallback, but "
                    + "modelProfile.routing().allowHostedFallback() is true");
        }
        Map<Feature, Boolean> overrides = config.featureOverrides();
        if (overrides != null) {
            for (Map.Entry<Feature, Boolean> e : overrides.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue()) && !profilePermits(profile, e.getKey())) {
                    throw new PolicyViolation("featureOverride enables " + e.getKey()
                            + ", which profile " + profile + " forbids (capability matrix is the ceiling)");
                }
            }
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new ConfigException("ApplicationPack is missing required provider/field: " + field);
        }
        return value;
    }

    private static void requireNoNulls(List<?> list, String field) {
        // Iterate rather than List.contains(null): JDK immutable lists throw NPE on a null probe.
        for (Object element : list) {
            if (element == null) {
                throw new ConfigException("ApplicationPack." + field + " must not contain null elements");
            }
        }
    }

    private static void requireUniquePageIds(List<PageDescriptor> pages) {
        Set<String> seen = new HashSet<>();
        for (PageDescriptor page : pages) {
            if (page == null) {
                throw new ConfigException("navigationCatalog.pages must not contain null elements");
            }
            if (!seen.add(page.pageId())) {
                throw new ConfigException("navigationCatalog has a duplicate pageId: " + page.pageId());
            }
        }
    }

    /**
     * Mirrors the config module's capability matrix (03-deployment-profiles): only
     * {@link Feature#HOSTED_MODELS} is profile-gated — permitted under {@code CLOUD} only. Every other
     * feature is permitted by the matrix in every profile (config may still leave it off).
     */
    private static boolean profilePermits(DeploymentProfile profile, Feature feature) {
        if (feature == Feature.HOSTED_MODELS) {
            return profile == DeploymentProfile.CLOUD;
        }
        return true;
    }
}
