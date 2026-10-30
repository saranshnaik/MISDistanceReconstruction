package com.moveinsync.distance.engine.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Where floating point stops and exact numbers start.
 *
 * Each segment distance is rounded once to a whole number of millimetres (a long). Totals are
 * plain long sums, so the order of adding can never change the answer. The only other rounding
 * is km to 2 decimals (HALF_UP) at the very end, done on exact decimals.
 */
public final class Millimetres {

    private Millimetres() {
    }

    public static long fromMeters(double meters) {
        return Math.round(meters * 1000.0);
    }

    /** Exact conversion, rounded half-up to 2 decimals: 1_005_000 mm is 1.01 km. */
    public static BigDecimal toKm(long millimetres) {
        return BigDecimal.valueOf(millimetres).divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);
    }
}
