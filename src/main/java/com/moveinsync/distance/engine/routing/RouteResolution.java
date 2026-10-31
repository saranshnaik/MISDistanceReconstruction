package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * Answers in the same order as the questions, plus what it cost: requests that really hit
 * the provider and distinct routes that were already known.
 */
public record RouteResolution(List<Double> meters, int providerCalls, int cacheHits) {
}
