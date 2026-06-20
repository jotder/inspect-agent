package com.eoiagent.core;

/**
 * How a deployment runs. Selected once at install via {@code eoiagent.profile} and fixed for the
 * lifetime of a {@code ConfigProvider}. Drives the capability matrix in
 * {@code docs/architecture/03-deployment-profiles.md}.
 *
 * <ul>
 *   <li>{@link #OFFLINE} — air-gapped; no network; fails closed.</li>
 *   <li>{@link #ON_PREM_HOSTED} — on-prem with a LAN model server; no internet egress.</li>
 *   <li>{@link #CLOUD} — hosted models permitted; internet egress allowed.</li>
 * </ul>
 */
public enum DeploymentProfile {
    OFFLINE,
    ON_PREM_HOSTED,
    CLOUD
}
