package com.moveinsync.distance.persistence;

import com.moveinsync.distance.engine.TripStatus;
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
 * One saved run of the engine for a trip: the answer, which input and rules produced it, what
 * routing cost, and every segment as JSON so a dispute can be answered line by line later.
 * Rows are only ever added, never edited.
 */
@Entity
@Table(name = "distance_computation")
public class DistanceComputationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private Long tripId;
    private String inputHash;
    private String ruleVersion;
    private long totalMm;
    private BigDecimal billableKm;
    private BigDecimal naiveKm;

    @Enumerated(EnumType.STRING)
    private TripStatus status;

    private int routeRequests;
    private int providerCalls;
    private int cacheHits;
    private String segmentsJson;
    private Instant computedAt;

    protected DistanceComputationEntity() {
        // for JPA
    }

    public DistanceComputationEntity(String tenantId, Long tripId, String inputHash, String ruleVersion,
                                     long totalMm, BigDecimal billableKm, BigDecimal naiveKm,
                                     TripStatus status,
                                     int routeRequests, int providerCalls, int cacheHits,
                                     String segmentsJson, Instant computedAt) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.inputHash = inputHash;
        this.ruleVersion = ruleVersion;
        this.totalMm = totalMm;
        this.billableKm = billableKm;
        this.naiveKm = naiveKm;
        this.status = status;
        this.routeRequests = routeRequests;
        this.providerCalls = providerCalls;
        this.cacheHits = cacheHits;
        this.segmentsJson = segmentsJson;
        this.computedAt = computedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTripId() {
        return tripId;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public long getTotalMm() {
        return totalMm;
    }

    public BigDecimal getBillableKm() {
        return billableKm;
    }

    public BigDecimal getNaiveKm() {
        return naiveKm;
    }

    public TripStatus getStatus() {
        return status;
    }

    public int getRouteRequests() {
        return routeRequests;
    }

    public int getProviderCalls() {
        return providerCalls;
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public String getSegmentsJson() {
        return segmentsJson;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
