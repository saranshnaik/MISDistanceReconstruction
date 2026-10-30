package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.geo.Millimetres;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Duration;

/**
 * Decides whether to believe the routing provider's answer for a real gap.
 *
 * Two ways to reject it (a rejected segment is billed as 0 km, but kept in the output with
 * its numbers so the reason can be read back later):
 *  1. the route is longer than 100 km, which no gap in a city trip should need
 *  2. the route would need a speed above the limit, AND even a generous detour on the
 *     straight line (aerial x 1.4) would also need a speed above the limit.
 *     Both must hold: if the straight line is believable we trust the provider's road
 *     distance, because roads are rarely straight.
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
}
