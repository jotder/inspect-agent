/**
 * The reference Application Pack (T-116): a complete, compiling, passing implementation of the
 * {@link com.eoiagent.app.ApplicationPack} SPI for the fictional "Acme Lakehouse Suite", runnable
 * fully offline. {@link com.eoiagent.app.reference.ReferenceApplicationPack} returns all eight
 * providers; onboarding a real product is "copy this module → rename → replace the sample content".
 *
 * <p>Depends on {@code eoiagent-app-api} + the BOM only — never on a core adapter module or an agent
 * framework (ADR-0011). It talks to core entirely through the SPI and ships realistic-but-generic
 * Acme content (datasets, ETL pipelines, KPI dashboards) under {@code src/main/resources/acme/}.
 */
package com.eoiagent.app.reference;
