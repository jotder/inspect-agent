package com.eoiagent.app;

import java.util.List;
import java.util.Optional;

/**
 * The pages / KPI routes a model may target with a {@code NavigationIntent}. {@code find(pageId)}
 * is the lookup Host Integration uses to validate a proposed intent. {@code pages()} may be empty;
 * {@code pageId}s are unique.
 */
public interface NavigationCatalog {

    /** All navigable pages this pack exposes. */
    List<PageDescriptor> pages();

    /** Looks up a page by id, empty when unknown. */
    Optional<PageDescriptor> find(String pageId);
}
