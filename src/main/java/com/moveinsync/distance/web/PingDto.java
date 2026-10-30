package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** One GPS reading. A missing accuracy counts as "cannot be trusted" and the ping is dropped. */
public record PingDto(@NotNull Instant time, @NotNull Double lat, @NotNull Double lon, Double accuracyM) {

    Ping toEngine() {
        return new Ping(time, new GeoPoint(lat, lon), accuracyM == null ? Double.NaN : accuracyM);
    }
}
