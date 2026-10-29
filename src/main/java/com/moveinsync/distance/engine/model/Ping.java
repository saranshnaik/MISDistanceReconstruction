package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.Objects;

/** One raw reading from the cab's GPS device. Accuracy is the device's error radius in metres. */
public record Ping(Instant time, GeoPoint point, double accuracyM) {

    public Ping {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(point, "point");
    }
}
