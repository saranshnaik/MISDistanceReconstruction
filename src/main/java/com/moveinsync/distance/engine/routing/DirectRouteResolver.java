package com.moveinsync.distance.engine.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * No cache, no batching: one provider request per gap. It is the "before" side of the
 * with/without caching comparison, and the simplest resolver for tests.
 */
public class DirectRouteResolver implements RouteResolver {

    private final RoutingProvider provider;

    public DirectRouteResolver(RoutingProvider provider) {
        this.provider = provider;
    }

    @Override
    public RouteResolution resolve(List<RoutePair> pairs) {
        List<Double> meters = new ArrayList<>(pairs.size());
        for (RoutePair pair : pairs) {
            meters.add(provider.drivingDistanceM(pair.from(), pair.to()));
        }
        return new RouteResolution(meters, pairs.size(), 0);
    }
}
