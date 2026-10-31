package com.moveinsync.distance.engine;

import com.moveinsync.distance.engine.cleaning.CleaningReport;
import com.moveinsync.distance.engine.model.Segment;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the engine knows about one trip's distance. totalMm is the exact sum of billedMm;
 * billableKm is that in km, half-up to 2 decimals (the only km rounding). naiveKm is the plain
 * "add up the pings" answer so the effect of gap filling is visible. routing is about cost,
 * not the answer: a warm cache costs less but gives identical segments and km. status is
 * DEGRADED when routing was down and some gaps are only estimates.
 */
public record TripDistanceResult(
        List<Segment> segments,
        long totalMm,
        BigDecimal billableKm,
        BigDecimal naiveKm,
        CleaningReport cleaning,
        int skippedAnchors,
        RoutingUsage routing,
        TripStatus status,
        String ruleVersion) {
}
