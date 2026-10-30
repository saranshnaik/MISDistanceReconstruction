package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One point on the rebuilt track: either a cleaned GPS ping or an anchor we added.
 * {@code ref} says who the anchor belongs to (an employee ref, or "OFFICE"); pings have none.
 */
public record TrackPoint(Instant time, GeoPoint point, PointSource source, String ref) {

    public TrackPoint {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(source, "source");
    }
}
