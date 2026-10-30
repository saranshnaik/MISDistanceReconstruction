package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * Turns all the route questions of one trip into answers, as cheaply as it can.
 * The engine gives it every gap of the trip at once so it can dedupe, cache and batch.
 */
public interface RouteResolver {

    RouteResolution resolve(List<RoutePair> pairs);
}
