package com.moveinsync.distance.engine.cleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RuleConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeleportFilterTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final Instant END = Instant.parse("2026-03-01T09:00:00Z");
    private static final double BASE_LAT = 12.90;
    private static final double LON = 77.60;
    /** Metres in one degree of latitude on the sphere used by Haversine. */
    private static final double METERS_PER_DEGREE = 111_195.08;

    private static Ping pingAt(int secondsAfterStart, double metersNorth) {
        return new Ping(START.plusSeconds(secondsAfterStart),
                new GeoPoint(BASE_LAT + metersNorth / METERS_PER_DEGREE, LON), 5);
    }

    private static CleanedPings clean(RuleConfig config, List<Ping> pings) {
        return new PingCleaner(config).clean(pings, START, END);
    }

    private static double totalMeters(List<Ping> pings) {
        double total = 0;
        for (int i = 1; i < pings.size(); i++) {
            total += Haversine.distanceM(pings.get(i - 1).point(), pings.get(i).point());
        }
        return total;
    }

    /** A cab moving north at 4 m/s (about 14 km/h), one ping every 5 seconds. */
    private static List<Ping> steadyDrive(int count) {
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pings.add(pingAt(i * 5, i * 20.0));
        }
        return pings;
    }

    @Test
    void burstOfBadFixesIsDroppedAndDistanceIsUnchanged() {
        List<Ping> clean = steadyDrive(40);
        List<Ping> withBurst = new ArrayList<>(clean);
        // case-study example: 9 km away for 4 seconds is about 8,000 km/h
        withBurst.add(pingAt(102, 9_000));
        withBurst.add(pingAt(103, 9_000));
        withBurst.add(pingAt(104, 9_000));

        CleanedPings result = clean(RuleConfig.defaults(), withBurst);

        assertEquals(3, result.report().teleports());
        assertEquals(clean.size(), result.pings().size());
        assertEquals(totalMeters(clean), totalMeters(result.pings()));
    }

    @Test
    void speedJustUnderTheLimitIsKeptAndJustOverIsDropped() {
        // 300 m in 10 s = 108 km/h (ok), 400 m in 10 s = 144 km/h (too fast)
        CleanedPings fastEnough = clean(RuleConfig.defaults(),
                List.of(pingAt(0, 0), pingAt(10, 300), pingAt(20, 600)));
        assertEquals(3, fastEnough.pings().size());

        CleanedPings tooFast = clean(RuleConfig.defaults(),
                List.of(pingAt(0, 0), pingAt(10, 0), pingAt(20, 400), pingAt(30, 0)));
        assertEquals(1, tooFast.report().teleports());
    }

    @Test
    void speedLimitIsConfigurable() {
        List<Ping> pings = List.of(pingAt(0, 0), pingAt(10, 0), pingAt(20, 400), pingAt(30, 400));

        assertEquals(1, clean(RuleConfig.defaults(), pings).report().teleports());
        assertEquals(0, clean(RuleConfig.defaults().withMaxSpeedKmh(200), pings).report().teleports());
    }

    @Test
    void badFirstPingIsDroppedBecauseTheSecondDoesNotConfirmIt() {
        List<Ping> pings = new ArrayList<>();
        pings.add(pingAt(0, 9_000)); // bad first fix, 9 km away
        pings.addAll(steadyDrive(10).stream()
                .map(p -> new Ping(p.time().plusSeconds(5), p.point(), p.accuracyM()))
                .toList());

        CleanedPings result = clean(RuleConfig.defaults(), pings);

        assertEquals(1, result.report().teleports());
        assertEquals(10, result.pings().size());
    }

    @Test
    void longGapAtNormalSpeedIsNotATeleport() {
        // 20 km in 30 minutes is 40 km/h: a real drive through a tunnel, not a jump
        CleanedPings result = clean(RuleConfig.defaults(),
                List.of(pingAt(0, 0), pingAt(10, 20), pingAt(1810, 20_020)));

        assertEquals(0, result.report().teleports());
        assertEquals(3, result.pings().size());
    }

    @Test
    void singlePingIsKept() {
        CleanedPings result = clean(RuleConfig.defaults(), List.of(pingAt(0, 0)));

        assertEquals(1, result.pings().size());
        assertEquals(0, result.report().teleports());
    }
}
