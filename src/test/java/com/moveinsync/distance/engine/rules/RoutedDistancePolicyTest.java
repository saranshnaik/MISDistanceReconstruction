package com.moveinsync.distance.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoutedDistancePolicyTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final double METERS_PER_DEGREE = 111_195.08;

    private final RoutedDistancePolicy policy = new RoutedDistancePolicy(RuleConfig.defaults());

    private static TrackPoint pointAt(int minutes, double metersNorth) {
        return new TrackPoint(START.plusSeconds(minutes * 60L),
                new GeoPoint(12.90 + metersNorth / METERS_PER_DEGREE, 77.60), PointSource.GPS_PING, null);
    }

    @Test
    void normalRouteIsAcceptedAndBilledAtTheRoutedDistance() {
        // 5 km as the crow flies, 6.5 km by road, 20 minutes: about 20 km/h
        Segment s = policy.judge(pointAt(0, 0), pointAt(20, 5_000), 6_500);

        assertEquals(SegmentRule.ROUTED, s.rule());
        assertEquals(6_500_000, s.billedMm());
        assertEquals(6_500_000, s.routedMm());
        assertEquals(20 * 60_000, s.durationMs());
    }

    @Test
    void exactly100KmIsStillAccepted() {
        Segment s = policy.judge(pointAt(0, 0), pointAt(120, 90_000), 100_000);

        assertEquals(SegmentRule.ROUTED, s.rule());
    }

    @Test
    void moreThan100KmIsRejectedAndBilledAsZero() {
        Segment s = policy.judge(pointAt(0, 0), pointAt(180, 90_000), 100_001);

        assertEquals(SegmentRule.REJECTED_DISTANCE, s.rule());
        assertEquals(0, s.billedMm());
        assertEquals(100_001_000, s.routedMm()); // still reported, so the reason is visible
    }

    @Test
    void impossibleSpeedOnBothTheRouteAndThePaddedStraightLineIsRejected() {
        // 40 km straight line in 10 minutes = 240 km/h, route says 45 km
        Segment s = policy.judge(pointAt(0, 0), pointAt(10, 40_000), 45_000);

        assertEquals(SegmentRule.REJECTED_SPEED, s.rule());
        assertEquals(0, s.billedMm());
    }

    @Test
    void fastRouteIsTrustedWhenThePaddedStraightLineIsBelievable() {
        // route 30 km in 10 min = 180 km/h (too fast), but straight line 5 km x 1.4 = 7 km
        // in 10 min = 42 km/h. Only one of the two checks fails, so we keep the provider's answer.
        Segment s = policy.judge(pointAt(0, 0), pointAt(10, 5_000), 30_000);

        assertEquals(SegmentRule.ROUTED, s.rule());
        assertEquals(30_000_000, s.billedMm());
    }

    @Test
    void speedLimitIsConfigurable() {
        RoutedDistancePolicy strict = new RoutedDistancePolicy(RuleConfig.defaults().withMaxSpeedKmh(30));

        // 10 km in 10 minutes = 60 km/h, padded straight line 14 km = 84 km/h
        assertEquals(SegmentRule.REJECTED_SPEED,
                strict.judge(pointAt(0, 0), pointAt(10, 8_000), 10_000).rule());
        assertEquals(SegmentRule.ROUTED,
                policy.judge(pointAt(0, 0), pointAt(10, 8_000), 10_000).rule());
    }

    @Test
    void zeroTimeBetweenFarApartPointsIsRejected() {
        Segment s = policy.judge(pointAt(0, 0), pointAt(0, 3_000), 3_500);

        assertEquals(SegmentRule.REJECTED_SPEED, s.rule());
    }
}
