package com.moveinsync.distance.engine.model;

/** A latitude/longitude pair in degrees. */
public record GeoPoint(double lat, double lon) {

    /**
     * Usable means real numbers, in range, and not (0, 0): lots of devices send (0, 0) when
     * they have no fix.
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
