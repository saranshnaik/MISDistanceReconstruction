package com.moveinsync.distance.engine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One employee's milestone on a trip. Everything except the ref, rider type and event can be
 * null: devices and apps often fail to report a location or time.
 *
 * @param actualPoint  where the driver marked the pickup/drop/no-show
 * @param actualTime   when the driver marked it
 * @param plannedPoint where the roster says it should happen (planned riders)
 * @param plannedTime  when the roster says it should happen (planned riders)
 * @param homePoint    the home address on the rider's profile (ad-hoc riders)
 * @param signInTime   when the ad-hoc rider signed in (ad-hoc riders)
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
