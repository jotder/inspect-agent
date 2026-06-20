package com.eoiagent.core;

import java.time.Instant;

/** A single inbound message from the user, with the page it was sent from. */
public record UserMessage(String text, PageContext page, Instant at) {
}
