package com.eoiagent.core;

/** A reference to a source backing part of an answer. */
public record Citation(String sourceId, String title, String locator) {
}
