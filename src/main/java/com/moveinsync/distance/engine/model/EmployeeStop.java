package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One employee's milestone on a trip. Apart from ref, rider type and event everything can be
 * null, since devices and apps often don't send a location or time. Planned riders carry a
 * planned point/time from the roster, ad-hoc riders a home point and sign-in time from the
 * profile; actual is what the driver marked.
 */
public record EmployeeStop(
        String employeeRef,
        RiderType riderType,
        StopEvent event,
        GeoPoint actualPoint,
        Instant actualTime,
        GeoPoint plannedPoint,
        Instant plannedTime,
        GeoPoint homePoint,
        Instant signInTime) {

    public EmployeeStop {
        Objects.requireNonNull(employeeRef, "employeeRef");
        Objects.requireNonNull(riderType, "riderType");
        Objects.requireNonNull(event, "event");
    }
}
