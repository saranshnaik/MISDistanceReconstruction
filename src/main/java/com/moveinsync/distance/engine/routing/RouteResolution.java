package com.moveinsync.distance.engine.routing;

import java.util.List;

/**
 * The answers to a list of route questions, plus what it cost to get them.
 *
 * @param meters        driving distances, in the same order as the questions
 * @param providerCalls how many requests actually went to the routing provider
 * @param cacheHits     how many distinct routes were already known and needed no request
 */
public record RouteResolution(List<Double> meters, int providerCalls, int cacheHits) {
}
