package com.moveinsync.distance.engine.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * This is where floating point ends. Each segment is rounded once to whole millimetres and
 * totals are plain long sums, so adding order can't change anything. The only other rounding
 * is km to 2 decimals (HALF_UP) at the very end, on exact decimals.
 */
public final class Millimetres {

    private Millimetres() {
    }

    public static long fromMeters(double meters) {
        return Math.round(meters * 1000.0);
    }

    /** 1_005_000 mm is 1.01 km (half-up, 2 decimals). */
    public static BigDecimal toKm(long millimetres) {
        return BigDecimal.valueOf(millimetres).divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP);
    }
}
