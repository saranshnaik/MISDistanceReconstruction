package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;

/**
 * A 0.001 degree square of the map (roughly 110 m). Nearby points fall in the same cell and
 * can share one cached route.
 */
public record GridCell(long latIndex, long lonIndex) {

    static final double CELL_SIZE_DEG = 0.001;

    public static GridCell of(GeoPoint point) {
        return new GridCell(
                (long) Math.floor(point.lat() / CELL_SIZE_DEG),
                (long) Math.floor(point.lon() / CELL_SIZE_DEG));
    }

    /** Middle of the square; routes are always asked for between centres. */
    public GeoPoint center() {
        return new GeoPoint((latIndex + 0.5) * CELL_SIZE_DEG, (lonIndex + 0.5) * CELL_SIZE_DEG);
    }
}
