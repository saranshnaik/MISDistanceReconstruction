package com.moveinsync.distance.engine.geo;

import com.moveinsync.distance.engine.model.GeoPoint;

/**
 * Straight-line distance on the earth's surface.
 * StrictMath instead of Math on purpose: Math may differ in the last digits between CPUs and
 * JVMs, and the same input has to give the same billed km everywhere.
 */
public final class Haversine {

    /** Mean earth radius in metres. */
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final double DEG_TO_RAD = StrictMath.PI / 180.0;

    private Haversine() {
    }

    /** Distance in metres. */
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

    /** km/h for {@code meters} covered in {@code seconds}. */
    public static double speedKmh(double meters, double seconds) {
        return meters / seconds * 3.6;
    }
}
