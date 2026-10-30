package com.moveinsync.distance.engine;

/**
 * What the routing cost for one trip: the "before and after caching" numbers.
 *
 * @param routeRequests gaps that needed a driving distance. Without any caching or batching
 *                      this is also the number of provider calls we would have paid for.
 * @param providerCalls requests that really went to the provider
 * @param cacheHits     distinct routes that were already known
 */
public record RoutingUsage(int routeRequests, int providerCalls, int cacheHits) {
}
