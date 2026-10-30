package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.geo.Haversine;

/** Speed helper shared by the rules. */
public final class Speed {

    private Speed() {
    }

    /**
     * km/h for covering {@code meters} in {@code durationMs}. Two points at the same instant
     * are fine if they are in the same place (speed 0); if they are apart the speed is infinite.
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
