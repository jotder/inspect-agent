package com.eoiagent.examples;

import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.ParamSpec;
import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.NavigationIntent;

import java.util.Map;
import java.util.Optional;

/**
 * Showcases the navigation catalog — the routable KPI/report pages a {@code NavigationIntent} may
 * target (the product's signature behaviour: route the user to the right page rather than re-deriving
 * data inline). Lists the pages and validates a "show me revenue" intent against the catalog.
 *
 * <p>Note: in Phase 1 the runtime answers in TEXT; emitting a NavigationIntent end-to-end from the
 * orchestrator is a Phase-2 feature. This demo shows the catalog contract the pack provides.
 */
public final class NavigationDemo {

    private NavigationDemo() {
    }

    public static void main(String[] args) {
        NavigationCatalog catalog = new ReferenceApplicationPack().navigationCatalog();

        DemoSupport.header("Navigation catalog (NavigationIntent targets)");
        for (PageDescriptor page : catalog.pages()) {
            DemoSupport.kv(page.pageId(), page.title() + " - " + page.description());
            for (ParamSpec param : page.params()) {
                DemoSupport.bullet("param " + param.name() + " (" + param.type() + ")"
                        + (param.required() ? " [required]" : " [optional]") + " - " + param.description());
            }
        }

        DemoSupport.header("Validating a 'show me revenue' navigation intent");
        NavigationIntent intent = new NavigationIntent("kpi-dashboard",
                Map.of("metric", "revenue"), "User asked to see revenue KPIs");
        Optional<PageDescriptor> target = catalog.find(intent.targetPageId());
        DemoSupport.kv("targetPageId", intent.targetPageId());
        DemoSupport.kv("parameters", intent.parameters());
        DemoSupport.kv("rationale", intent.rationale());
        DemoSupport.kv("resolves to a catalog page", target.isPresent());
        target.ifPresent(page -> DemoSupport.kv("page title", page.title()));
    }
}
