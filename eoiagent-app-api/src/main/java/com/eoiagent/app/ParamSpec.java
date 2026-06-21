package com.eoiagent.app;

/** One navigation parameter: its name, type, whether it is required, and a human-readable description. */
public record ParamSpec(String name, String type, boolean required, String description) {
}
