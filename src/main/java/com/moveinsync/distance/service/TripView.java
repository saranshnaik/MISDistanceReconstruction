package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.model.TripType;
import com.moveinsync.distance.persistence.BillingState;
import com.moveinsync.distance.persistence.DistanceComputationEntity;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.TripEntity;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What we tell callers about a trip: its state and the latest computed distance.
 * billedKm is what the invoice said when it was closed and never changes; finalBilledKm is
 * billedKm plus every signed adjustment since (null while the trip is unbilled).
 */
public record TripView(
        String tripRef,
        TripType tripType,
        Instant tripStart,
        Instant tripEnd,
        DistanceState distanceState,
        BillingState billingState,
        BigDecimal billableKm,
        BigDecimal naiveKm,
        String ruleVersion,
        int routeRequests,
        int providerCalls,
        int cacheHits,
        Long computationId,
        Instant computedAt,
        BigDecimal billedKm,
        BigDecimal finalBilledKm) {

    public static TripView of(TripEntity trip, DistanceComputationEntity latest, BigDecimal finalBilledKm) {
        return new TripView(trip.getTripRef(), trip.getTripType(), trip.getTripStart(), trip.getTripEnd(),
                trip.getDistanceState(), trip.getBillingState(),
                latest.getBillableKm(), latest.getNaiveKm(), latest.getRuleVersion(),
                latest.getRouteRequests(), latest.getProviderCalls(), latest.getCacheHits(),
                latest.getId(), latest.getComputedAt(), trip.getBilledKm(), finalBilledKm);
    }
}
