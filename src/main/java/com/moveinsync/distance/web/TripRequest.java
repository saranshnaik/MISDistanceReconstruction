package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** The body of POST /api/trips: all the evidence for one trip. */
public record TripRequest(
        @NotBlank @Size(max = 128) String tripRef,
        @NotNull TripType type,
        @NotNull Instant tripStart,
        @NotNull Instant tripEnd,
        @Valid PointDto actualStart,
        @Valid PointDto actualEnd,
        @NotNull @Valid PointDto office,
        @Size(max = 50_000) List<@NotNull @Valid PingDto> pings,
        @Size(max = 500) List<@NotNull @Valid StopDto> stops) {

    @AssertTrue(message = "tripEnd must be after tripStart")
    boolean isTripWindowValid() {
        return tripStart == null || tripEnd == null || tripEnd.isAfter(tripStart);
    }

    @AssertTrue(message = "office must be a real coordinate")
    boolean isOfficeValid() {
        return office == null || office.lat() == null || office.lon() == null || office.toEngine().isValid();
    }

    public TripEvidence toEngine() {
        List<Ping> enginePings = pings == null ? List.of() : pings.stream().map(PingDto::toEngine).toList();
        List<EmployeeStop> engineStops = stops == null ? List.of() : stops.stream().map(StopDto::toEngine).toList();
        return new TripEvidence(type, tripStart, tripEnd,
                PointDto.toEngine(actualStart), PointDto.toEngine(actualEnd), office.toEngine(),
                enginePings, engineStops);
    }
}
