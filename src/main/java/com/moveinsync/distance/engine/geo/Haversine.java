package com.moveinsync.distance.engine.geo;

import com.moveinsync.distance.engine.model.GeoPoint;

/**
 * Straight-line ("as the crow flies") distance on the earth's surface.
 *
 * Uses StrictMath instead of Math. Math is allowed to give slightly different last digits on
 * different CPUs/JVMs; StrictMath always gives the same bits. That matters because the same
 * input must always give exactly the same billed kilometres.
 */
public final class Haversine {

    /** Mean earth radius in metres (IUGG). */
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final double DEG_TO_RAD = StrictMath.PI / 180.0;

    private Haversine() {
    }

    /** Distance between two points in metres. */
    public static double distanceM(GeoPoint a, GeoPoint b) {
        double lat1 = a.lat() * DEG_TO_RAD;
        double lat2 = b.lat() * DEG_TO_RAD;
        double dLat = lat2 - lat1;
        double dLon = (b.lon() - a.lon()) * DEG_TO_RAD;

        double sinHalfLat = StrictMath.sin(dLat / 2);
        double sinHalfLon = StrictMath.sin(dLon / 2);
        double h = sinHalfLat * sinHalfLat
                + StrictMath.cos(lat1) * StrictMath.cos(lat2) * sinHalfLon * sinHalfLon;

        return 2 * EARTH_RADIUS_M * StrictMath.asin(StrictMath.sqrt(h));
    }

    /** Speed in km/h for covering {@code meters} in {@code seconds}. */
    public static double speedKmh(double meters, double seconds) {
        return meters / seconds * 3.6;
    }
}
