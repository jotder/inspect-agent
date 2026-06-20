package com.eoiagent.core;

/** Thrown when a tool invocation fails to execute. */
public class ToolExecutionException extends EoiAgentException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
