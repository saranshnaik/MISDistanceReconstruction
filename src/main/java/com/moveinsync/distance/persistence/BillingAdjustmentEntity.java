package com.moveinsync.distance.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Signed correction to a closed invoice, e.g. +1.80 km once late pings arrive. */
@Entity
@Table(name = "billing_adjustment")
public class BillingAdjustmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private Long tripId;
    private Long computationId;
    private BigDecimal deltaKm;
    private String reason;
    private Instant createdAt;

    protected BillingAdjustmentEntity() {
        // for JPA
    }

    public BillingAdjustmentEntity(String tenantId, Long tripId, Long computationId,
                                   BigDecimal deltaKm, String reason, Instant createdAt) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.computationId = computationId;
        this.deltaKm = deltaKm;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTripId() {
        return tripId;
    }

    public Long getComputationId() {
        return computationId;
    }

    public BigDecimal getDeltaKm() {
        return deltaKm;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
