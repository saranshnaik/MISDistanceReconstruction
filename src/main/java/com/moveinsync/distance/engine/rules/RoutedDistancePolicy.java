package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.geo.Millimetres;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Duration;

/**
 * Do we believe the provider's answer for a real gap? Reject (bill 0, but keep the numbers
 * in the output so the reason can be read back) if the route is over 100 km, or if the route
 * needs a speed above the limit AND so would the straight line with a generous detour
 * (aerial x 1.4). Both have to hold: if the straight line is believable we trust the road
 * distance, roads are rarely straight.
 */
public class RoutedDistancePolicy {

    private final RuleConfig config;

    public RoutedDistancePolicy(RuleConfig config) {
        this.config = config;
    }

    public Segment judge(TrackPoint from, TrackPoint to, double routedMeters) {
        long durationMs = Duration.between(from.time(), to.time()).toMillis();
        double aerialMeters = Haversine.distanceM(from.point(), to.point());
        long aerialMm = Millimetres.fromMeters(aerialMeters);
        long routedMm = Millimetres.fromMeters(routedMeters);

        if (routedMeters > config.maxRoutedKm() * 1000.0) {
            return new Segment(from, to, durationMs, aerialMm, routedMm, 0, SegmentRule.REJECTED_DISTANCE);
        }

        double routedSpeed = Speed.kmh(routedMeters, durationMs);
        double paddedAerialSpeed = Speed.kmh(aerialMeters * config.detourFactor(), durationMs);
        if (routedSpeed > config.maxSpeedKmh() && paddedAerialSpeed > config.maxSpeedKmh()) {
            return new Segment(from, to, durationMs, aerialMm, routedMm, 0, SegmentRule.REJECTED_SPEED);
        }

        return new Segment(from, to, durationMs, aerialMm, routedMm, routedMm, SegmentRule.ROUTED);
    }

    /**
     * Used when the provider is down: straight line x 1.4, a typical detour. Only an estimate, so
     * the trip is DEGRADED and gets recomputed later. Nothing is rejected here since there is no
     * route to compare with.
     */
    public Segment degradedEstimate(TrackPoint from, TrackPoint to) {
        long durationMs = Duration.between(from.time(), to.time()).toMillis();
        double aerialMeters = Haversine.distanceM(from.point(), to.point());
        long estimateMm = Millimetres.fromMeters(aerialMeters * config.detourFactor());
        return new Segment(from, to, durationMs, Millimetres.fromMeters(aerialMeters), null,
                estimateMm, SegmentRule.DEGRADED_ESTIMATE);
    }
}
