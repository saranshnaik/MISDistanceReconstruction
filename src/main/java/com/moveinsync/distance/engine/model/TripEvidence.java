package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything we know about one trip. This is the only input the engine needs.
 *
 * @param actualStartPoint where the device says the trip started (may be null)
 * @param actualEndPoint   where the device says the trip ended (may be null)
 * @param office           the planned office location
 */
public record TripEvidence(
        TripType type,
        Instant tripStart,
        Instant tripEnd,
        GeoPoint actualStartPoint,
        GeoPoint actualEndPoint,
        GeoPoint office,
        List<Ping> pings,
        List<EmployeeStop> stops) {

    public TripEvidence {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tripStart, "tripStart");
        Objects.requireNonNull(tripEnd, "tripEnd");
        Objects.requireNonNull(office, "office");
        pings = pings == null ? List.of() : List.copyOf(pings);
        stops = stops == null ? List.of() : List.copyOf(stops);
    }
}
