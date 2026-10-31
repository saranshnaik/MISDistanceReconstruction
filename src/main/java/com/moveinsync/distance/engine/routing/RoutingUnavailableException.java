package com.moveinsync.distance.engine.routing;

/** Provider couldn't answer (down, timed out, rate limited). */
public class RoutingUnavailableException extends RuntimeException {

    public RoutingUnavailableException(String message) {
        super(message);
    }

    public RoutingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
