package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * Puts a CircuitBreaker in front of any RouteResolver. While the circuit is open it throws
 * RoutingUnavailableException at once, without touching the provider, so a dead provider costs
 * neither money nor time. The engine turns that exception into a DEGRADED estimate.
 */
public class CircuitBreakingRouteResolver implements RouteResolver {

    private final RouteResolver delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakingRouteResolver(RouteResolver delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public RouteResolution resolve(List<RoutePair> pairs) {
        if (!breaker.allowRequest()) {
            throw new RoutingUnavailableException("routing circuit is open");
        }
        try {
            RouteResolution resolution = delegate.resolve(pairs);
            breaker.recordSuccess();
            return resolution;
        } catch (RoutingUnavailableException e) {
            breaker.recordFailure();
            throw e;
        }
    }

    public CircuitBreaker breaker() {
        return breaker;
    }
}
