package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.model.GeoPoint;
import jakarta.validation.constraints.NotNull;

/**
 * A coordinate as sent by a client. Range checks are left to the engine on purpose: a device
 * reporting latitude 95 should lose that one point, not get the whole trip rejected.
 */
public record PointDto(@NotNull Double lat, @NotNull Double lon) {

    GeoPoint toEngine() {
        return new GeoPoint(lat, lon);
    }

    static GeoPoint toEngine(PointDto dto) {
        return dto == null ? null : dto.toEngine();
    }
}
