package com.eoiagent.core;

/**
 * Root of the platform's typed exception hierarchy (conventions §5). Ports throw this or one of
 * its subclasses; callers never catch a raw {@code RuntimeException} from a port.
 */
public class EoiAgentException extends RuntimeException {

    public EoiAgentException(String message) {
        super(message);
    }

    public EoiAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
