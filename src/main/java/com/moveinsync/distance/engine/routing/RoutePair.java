package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;

/** One question for the routing provider: how far is it to drive from here to there? */
public record RoutePair(GeoPoint from, GeoPoint to) {
}
