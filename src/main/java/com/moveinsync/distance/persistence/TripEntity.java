package com.moveinsync.distance.persistence;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.TripType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One trip of one tenant. Fields are private and only change through the methods below, so
 * the rules ("a billed trip stays billed") live in one place.
 */
@Entity
@Table(name = "trip")
public class TripEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private String tripRef;

    @Enumerated(EnumType.STRING)
    private TripType tripType;

    private Instant tripStart;
    private Instant tripEnd;

    private Double actualStartLat;
    private Double actualStartLon;
    private Double actualEndLat;
    private Double actualEndLon;
    private double officeLat;
    private double officeLon;

    @Enumerated(EnumType.STRING)
    private DistanceState distanceState = DistanceState.PENDING;

    @Enumerated(EnumType.STRING)
    private BillingState billingState = BillingState.UNBILLED;

    private Long currentComputationId;
    private BigDecimal billedKm;
    private Long billedComputationId;
    private Instant createdAt;

    protected TripEntity() {
        // for JPA
    }

    public TripEntity(String tenantId, String tripRef, TripType tripType, Instant tripStart, Instant tripEnd,
                      GeoPoint actualStart, GeoPoint actualEnd, GeoPoint office, Instant createdAt) {
        this.tenantId = tenantId;
        this.tripRef = tripRef;
        this.tripType = tripType;
        this.tripStart = tripStart;
        this.tripEnd = tripEnd;
        this.actualStartLat = Points.lat(actualStart);
        this.actualStartLon = Points.lon(actualStart);
        this.actualEndLat = Points.lat(actualEnd);
        this.actualEndLon = Points.lon(actualEnd);
        this.officeLat = office.lat();
        this.officeLon = office.lon();
        this.createdAt = createdAt;
    }

    /** Remember the latest computation and whether it was a full answer or an estimate. */
    public void useComputation(Long computationId, DistanceState state) {
        this.currentComputationId = computationId;
        this.distanceState = state;
    }

    /** Close the invoice at the given distance. From now on only adjustments change the money. */
    public void markBilled(BigDecimal km, Long computationId) {
        this.billingState = BillingState.BILLED;
        this.billedKm = km;
        this.billedComputationId = computationId;
    }

    public boolean isBilled() {
        return billingState == BillingState.BILLED;
    }

    public GeoPoint actualStartPoint() {
        return Points.of(actualStartLat, actualStartLon);
    }

    public GeoPoint actualEndPoint() {
        return Points.of(actualEndLat, actualEndLon);
    }

    public GeoPoint officePoint() {
        return new GeoPoint(officeLat, officeLon);
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTripRef() {
        return tripRef;
    }

    public TripType getTripType() {
        return tripType;
    }

    public Instant getTripStart() {
        return tripStart;
    }

    public Instant getTripEnd() {
        return tripEnd;
    }

    public DistanceState getDistanceState() {
        return distanceState;
    }

    public BillingState getBillingState() {
        return billingState;
    }

    public Long getCurrentComputationId() {
        return currentComputationId;
    }

    public BigDecimal getBilledKm() {
        return billedKm;
    }

    public Long getBilledComputationId() {
        return billedComputationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
