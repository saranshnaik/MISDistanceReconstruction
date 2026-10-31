package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything known about one trip; the only input the engine needs. The actual start/end
 * points come from the device and may be null, office is the planned location.
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
