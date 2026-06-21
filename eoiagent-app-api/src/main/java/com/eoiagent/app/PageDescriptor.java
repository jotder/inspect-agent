package com.eoiagent.app;

import java.util.List;

/** A navigable page the model may target: its id, title, description and the params it accepts. */
public record PageDescriptor(String pageId, String title, String description, List<ParamSpec> params) {
}
