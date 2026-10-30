package com.moveinsync.distance.engine;

import com.moveinsync.distance.engine.cleaning.CleaningReport;
import com.moveinsync.distance.engine.model.Segment;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the engine knows about one trip's distance.
 *
 * @param totalMm         sum of billedMm over all segments, exact
 * @param billableKm      totalMm in km, rounded half-up to 2 decimals (the only rounding of km)
 * @param naiveKm         what a plain "add up the distance between pings" would have said,
 *                        so the effect of the gap filling is visible
 * @param skippedAnchors  milestones that had no usable location or time
 * @param ruleVersion     which rules and thresholds produced this
 */
public record TripDistanceResult(
        List<Segment> segments,
        long totalMm,
        BigDecimal billableKm,
        BigDecimal naiveKm,
        CleaningReport cleaning,
        int skippedAnchors,
        String ruleVersion) {
}
