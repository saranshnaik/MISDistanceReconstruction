package com.moveinsync.distance.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AerialRulesTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final double METERS_PER_DEGREE = 111_195.08;

    private final ShortGapAerialRule shortGap = new ShortGapAerialRule(RuleConfig.defaults());
    private final ParkedRule parked = new ParkedRule(RuleConfig.defaults());

    private static TrackPoint pointAt(int seconds, double metersNorth) {
        return new TrackPoint(START.plusSeconds(seconds),
                new GeoPoint(12.90 + metersNorth / METERS_PER_DEGREE, 77.60), PointSource.GPS_PING, null);
    }

    @Test
    void shortGapAt69SecondsApplies() {
        Optional<Segment> segment = shortGap.apply(pointAt(0, 0), pointAt(69, 500));

        assertTrue(segment.isPresent());
        assertEquals(SegmentRule.SHORT_GAP_AERIAL, segment.get().rule());
    }

    @Test
    void gapOf70SecondsIsNoLongerShort() {
        assertTrue(shortGap.apply(pointAt(0, 0), pointAt(70, 500)).isEmpty());
    }

    @Test
    void shortGapAtImpossibleSpeedDoesNotApply() {
        // 1 km in 10 s = 360 km/h
        assertTrue(shortGap.apply(pointAt(0, 0), pointAt(10, 1000)).isEmpty());
    }

    @Test
    void shortGapSegmentBillsTheStraightLineInMillimetres() {
        Segment s = shortGap.apply(pointAt(0, 0), pointAt(30, 250)).orElseThrow();

        assertEquals(250_000, s.aerialMm(), 5); // 250 m, give or take rounding of the input
        assertEquals(s.aerialMm(), s.billedMm());
        assertNull(s.routedMm());
        assertEquals(30_000, s.durationMs());
    }

    @Test
    void twoPointsAtTheSameInstantAndPlaceCostNothing() {
        Segment s = shortGap.apply(pointAt(5, 100), pointAt(5, 100)).orElseThrow();

        assertEquals(0, s.billedMm());
    }

    @Test
    void twoPointsAtTheSameInstantButApartAreNotShortGap() {
        assertTrue(shortGap.apply(pointAt(5, 0), pointAt(5, 100)).isEmpty());
    }

    @Test
    void slowLongGapIsParked() {
        // 136 m in 100 s = 4.9 km/h
        Optional<Segment> segment = parked.apply(pointAt(0, 0), pointAt(100, 136));

        assertTrue(segment.isPresent());
        assertEquals(SegmentRule.PARKED, segment.get().rule());
        assertEquals(segment.get().aerialMm(), segment.get().billedMm());
    }

    @Test
    void longGapAt5KmhOrMoreIsNotParked() {
        // 142 m in 100 s = 5.1 km/h
        assertTrue(parked.apply(pointAt(0, 0), pointAt(100, 142)).isEmpty());
    }

    @Test
    void shortGapIsNeverParkedEvenIfSlow() {
        assertTrue(parked.apply(pointAt(0, 0), pointAt(40, 10)).isEmpty());
    }

    @Test
    void parkedAppliesToAHugeGapWithNoMovement() {
        Segment s = parked.apply(pointAt(0, 0), pointAt(3_000, 0)).orElseThrow();

        assertEquals(0, s.billedMm());
        assertEquals(SegmentRule.PARKED, s.rule());
    }

    @Test
    void aSegmentIsNeverClaimedByBothRules() {
        for (int seconds : new int[] {0, 10, 69, 70, 71, 300}) {
            for (double meters : new double[] {0, 5, 100, 500, 5_000}) {
                boolean both = shortGap.apply(pointAt(0, 0), pointAt(seconds, meters)).isPresent()
                        && parked.apply(pointAt(0, 0), pointAt(seconds, meters)).isPresent();
                assertEquals(false, both, seconds + " s / " + meters + " m");
            }
        }
    }
}
