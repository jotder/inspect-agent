/**
 * The Application Pack SPI (ADR-0011): the typed, copy-to-start contract a product implements to
 * instantiate the agent for its domain. {@link com.eoiagent.app.ApplicationPack} is the root; it
 * returns eight providers ({@link com.eoiagent.app.PackMetadata},
 * {@link com.eoiagent.app.ModelProfile}, {@link com.eoiagent.app.KnowledgeSource},
 * {@link com.eoiagent.app.ToolProvider}, {@link com.eoiagent.app.NavigationCatalog},
 * {@link com.eoiagent.app.PromptProfile}, {@link com.eoiagent.app.PolicyProfile},
 * {@link com.eoiagent.app.PackConfig}) plus their value records.
 *
 * <p>CORE consumes a pack — never the reverse. This module depends on {@code eoiagent-core} domain
 * types only: no agent framework, no core adapter module.
 */
package com.eoiagent.app;
