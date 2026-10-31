package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * Turns all route questions of a trip into answers as cheaply as it can. It gets every gap
 * at once so it can dedupe, cache and batch.
 */
public interface RouteResolver {

    RouteResolution resolve(List<RoutePair> pairs);
}
