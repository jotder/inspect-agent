package com.eoiagent.scratchpad;

import com.eoiagent.core.EoiAgentException;

/**
 * Root of the scratchpad module's typed exceptions (subtype of {@link EoiAgentException}, per
 * conventions §5). Raised for capacity and I/O faults; {@link ScratchpadKeyNotFound} is the
 * not-found subtype.
 */
public class ScratchpadException extends EoiAgentException {

    public ScratchpadException(String message) {
        super(message);
    }

    public ScratchpadException(String message, Throwable cause) {
        super(message, cause);
    }
}
