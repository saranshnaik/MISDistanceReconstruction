package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;

/**
 * Whoever knows the driving distance between two points (a maps API in real life).
 * The engine only sees this interface, so caching, circuit breaking and the fake provider
 * used in tests can all be plugged in behind it.
 */
public interface RoutingProvider {

    /** Driving distance in metres from {@code from} to {@code to}. */
    double drivingDistanceM(GeoPoint from, GeoPoint to);
}
