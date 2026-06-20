package com.eoiagent.core;

/** Thrown when no model is available to satisfy a requested role/profile. */
public class ModelUnavailableException extends EoiAgentException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
