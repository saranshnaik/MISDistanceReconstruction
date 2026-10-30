package com.moveinsync.distance.persistence;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An employee's pickup / drop / no-show on a trip, stored the way the engine reads it. */
@Entity
@Table(name = "trip_stop")
public class TripStopEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private Long tripId;
    private String employeeRef;

    @Enumerated(EnumType.STRING)
    private RiderType riderType;

    @Enumerated(EnumType.STRING)
    private StopEvent event;

    private Double actualLat;
    private Double actualLon;
    private Instant actualTime;
    private Double plannedLat;
    private Double plannedLon;
    private Instant plannedTime;
    private Double homeLat;
    private Double homeLon;
    private Instant signInTime;

    protected TripStopEntity() {
        // for JPA
    }

    public TripStopEntity(String tenantId, Long tripId, EmployeeStop stop) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.employeeRef = stop.employeeRef();
        this.riderType = stop.riderType();
        this.event = stop.event();
        this.actualLat = Points.lat(stop.actualPoint());
        this.actualLon = Points.lon(stop.actualPoint());
        this.actualTime = stop.actualTime();
        this.plannedLat = Points.lat(stop.plannedPoint());
        this.plannedLon = Points.lon(stop.plannedPoint());
        this.plannedTime = stop.plannedTime();
        this.homeLat = Points.lat(stop.homePoint());
        this.homeLon = Points.lon(stop.homePoint());
        this.signInTime = stop.signInTime();
    }

    public EmployeeStop toEngine() {
        return new EmployeeStop(employeeRef, riderType, event,
                Points.of(actualLat, actualLon), actualTime,
                Points.of(plannedLat, plannedLon), plannedTime,
                Points.of(homeLat, homeLon), signInTime);
    }

    public Long getId() {
        return id;
    }

    public Long getTripId() {
        return tripId;
    }

    public String getEmployeeRef() {
        return employeeRef;
    }
}
