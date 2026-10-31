package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.geo.Haversine;

/** Speed helper shared by the rules. */
public final class Speed {

    private Speed() {
    }

    /**
     * km/h for {@code meters} in {@code durationMs}. Same instant and same place is speed 0;
     * same instant but apart is infinite.
     */
    public static double kmh(double meters, long durationMs) {
        if (meters == 0) {
            return 0;
        }
        if (durationMs <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Haversine.speedKmh(meters, durationMs / 1000.0);
    }
}
