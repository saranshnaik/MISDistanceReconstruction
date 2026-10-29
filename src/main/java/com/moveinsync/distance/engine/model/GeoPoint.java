package com.moveinsync.distance.engine.model;

/** A latitude/longitude pair in degrees. */
public record GeoPoint(double lat, double lon) {

    /**
     * A point is usable if both numbers are real, inside the valid range, and not (0, 0).
     * (0, 0) is what many devices send when they have no fix at all.
     */
    public boolean isValid() {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return false;
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return false;
        }
        return !(lat == 0 && lon == 0);
    }
}
