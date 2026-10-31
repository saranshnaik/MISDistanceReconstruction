package com.moveinsync.distance.engine;

/**
 * What routing cost for one trip, the before/after caching numbers. routeRequests is the
 * number of gaps that needed a distance (also what we'd pay with no caching or batching),
 * providerCalls the requests that really went out, cacheHits the routes already known.
 */
public record RoutingUsage(int routeRequests, int providerCalls, int cacheHits) {
}
