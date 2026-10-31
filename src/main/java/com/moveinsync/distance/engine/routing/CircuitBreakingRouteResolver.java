package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * Puts a CircuitBreaker in front of any RouteResolver. While it is open we throw
 * RoutingUnavailableException straight away without touching the provider, so a dead provider
 * costs no money and no time. The engine turns that into a DEGRADED estimate.
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
