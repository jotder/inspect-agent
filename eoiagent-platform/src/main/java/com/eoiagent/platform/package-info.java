/**
 * Platform bootstrap (T-010): the core assembly module. {@link com.eoiagent.platform.PlatformBuilder}
 * consumes an {@link com.eoiagent.app.ApplicationPack} and wires the core adapters into a ready
 * {@link com.eoiagent.host.AgentService}, returned as an {@link com.eoiagent.platform.AgentPlatform}
 * (Flow 0). {@link com.eoiagent.platform.PackValidator} fails the pack fast at {@code start()};
 * {@link com.eoiagent.platform.ProfilePolicyEngine} adapts the pack's {@code PolicyProfile} to the
 * core {@code PolicyEngine} port.
 *
 * <p>CORE never depends on a pack, and the pack never sees this module — the dependency points
 * one way (ADR-0011). The {@code AppId} from the pack's metadata is stamped into every
 * {@code AgentContext} and {@code AuditEvent}.
 */
package com.eoiagent.platform;
