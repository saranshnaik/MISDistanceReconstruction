package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A point on the rebuilt track, either a cleaned ping or an anchor. ref is the employee ref
 * or "OFFICE" for anchors, null for pings.
 */
public record TrackPoint(Instant time, GeoPoint point, PointSource source, String ref) {

    public TrackPoint {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(source, "source");
    }
}
