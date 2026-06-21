package com.eoiagent.app;

import java.util.List;

/**
 * The root SPI a product implements to instantiate the agent for its domain (ADR-0011). CORE
 * consumes a pack — never the reverse. {@code eoiagent-platform} validates and assembles a pack
 * into a ready {@code AgentService} at {@code start()}; all methods are called once at assembly
 * unless noted in the spec.
 *
 * @see <a href="file:../../../../../docs/specs/application-pack.md">application-pack spec</a>
 */
public interface ApplicationPack {

    /** Identity of this pack; {@code appId} is unique per product and stable across versions. */
    PackMetadata metadata();

    /** Which models/endpoints to use and how to route between them. */
    ModelProfile modelProfile();

    /** Domain corpus to ingest; may be empty (a pack with no RAG corpus is valid). */
    List<KnowledgeSource> knowledgeSources();

    /** Host Java-API tools + MCP servers exposed to the agent. */
    ToolProvider toolProvider();

    /** Pages / KPI routes the model may target with a {@code NavigationIntent}. */
    NavigationCatalog navigationCatalog();

    /** System prompts, persona and domain glossary for the model. */
    PromptProfile promptProfile();

    /** Host-role mapping and per-role capability grants. */
    PolicyProfile policyProfile();

    /** Deployment profile + feature overrides + shipped config defaults. */
    PackConfig config();
}
