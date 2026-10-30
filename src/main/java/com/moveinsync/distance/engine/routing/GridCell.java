package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;

/**
 * A square of the map, 0.001 degrees on each side (roughly 110 m). Every point inside the
 * square belongs to the same cell, so two nearby points can share one cached route.
 */
public record GridCell(long latIndex, long lonIndex) {

    static final double CELL_SIZE_DEG = 0.001;

    public static GridCell of(GeoPoint point) {
        return new GridCell(
                (long) Math.floor(point.lat() / CELL_SIZE_DEG),
                (long) Math.floor(point.lon() / CELL_SIZE_DEG));
    }

    /** The middle of the square. Routes are always asked for between cell centres. */
    public GeoPoint center() {
        return new GeoPoint((latIndex + 0.5) * CELL_SIZE_DEG, (lonIndex + 0.5) * CELL_SIZE_DEG);
    }
}
