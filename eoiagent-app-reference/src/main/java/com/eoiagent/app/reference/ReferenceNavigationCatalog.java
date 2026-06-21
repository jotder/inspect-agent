package com.eoiagent.app.reference;

import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.ParamSpec;

import java.util.List;
import java.util.Optional;

/**
 * The host's routable KPI/report pages — the targets a {@code NavigationIntent} may point at.
 * {@code find(pageId)} powers the Host Integration's validation of a model-proposed intent. Page ids
 * are unique.
 */
final class ReferenceNavigationCatalog implements NavigationCatalog {

    private static final List<PageDescriptor> PAGES = List.of(
            new PageDescriptor("kpi-dashboard", "KPI Dashboard", "Revenue/usage KPIs by period",
                    List.of(new ParamSpec("metric", "string", true, "e.g. revenue"),
                            new ParamSpec("period", "string", false, "e.g. last-quarter"))),
            new PageDescriptor("pipeline-detail", "Pipeline Detail", "One ETL pipeline's runs",
                    List.of(new ParamSpec("pipelineId", "string", true, "pipeline id"))),
            new PageDescriptor("incident-detail", "Incident Detail", "A data-quality incident",
                    List.of(new ParamSpec("incidentId", "string", true, "incident id"))));

    @Override
    public List<PageDescriptor> pages() {
        return PAGES;
    }

    @Override
    public Optional<PageDescriptor> find(String pageId) {
        return PAGES.stream().filter(p -> p.pageId().equals(pageId)).findFirst();
    }
}
