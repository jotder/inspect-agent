package com.eoiagent.app;

import java.util.List;

/** Provider preference order plus whether hosted fallback is permitted (must be false under OFFLINE). */
public record RoutingPolicy(List<String> order, boolean allowHostedFallback) {
}
