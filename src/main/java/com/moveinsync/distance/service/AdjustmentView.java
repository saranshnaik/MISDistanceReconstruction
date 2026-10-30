package com.moveinsync.distance.service;

import com.moveinsync.distance.persistence.BillingAdjustmentEntity;
import java.math.BigDecimal;
import java.time.Instant;

/** One signed correction to a closed invoice, e.g. +1.80 km. */
public record AdjustmentView(BigDecimal deltaKm, String reason, Long computationId, Instant createdAt) {

    static AdjustmentView of(BillingAdjustmentEntity adjustment) {
        return new AdjustmentView(adjustment.getDeltaKm(), adjustment.getReason(),
                adjustment.getComputationId(), adjustment.getCreatedAt());
    }
}
