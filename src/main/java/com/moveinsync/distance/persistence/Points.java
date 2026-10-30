package com.moveinsync.distance.persistence;

import com.moveinsync.distance.engine.model.GeoPoint;

/** Small helpers for storing optional points as two nullable columns. */
final class Points {

    private Points() {
    }

    static GeoPoint of(Double lat, Double lon) {
        return lat == null || lon == null ? null : new GeoPoint(lat, lon);
    }

    static Double lat(GeoPoint point) {
        return point == null ? null : point.lat();
    }

    static Double lon(GeoPoint point) {
        return point == null ? null : point.lon();
    }
}
